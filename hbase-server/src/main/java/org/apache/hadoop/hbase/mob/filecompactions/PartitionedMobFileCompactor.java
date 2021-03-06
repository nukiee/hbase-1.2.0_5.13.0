/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.mob.filecompactions;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.KeyValueUtil;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.Tag;
import org.apache.hadoop.hbase.TagType;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.MobCompactPartitionPolicy;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.HFileLink;
import org.apache.hadoop.hbase.io.hfile.CacheConfig;
import org.apache.hadoop.hbase.mapreduce.LoadIncrementalHFiles;
import org.apache.hadoop.hbase.mob.MobConstants;
import org.apache.hadoop.hbase.mob.MobFileName;
import org.apache.hadoop.hbase.mob.MobUtils;
import org.apache.hadoop.hbase.mob.filecompactions.MobFileCompactionRequest.CompactionType;
import org.apache.hadoop.hbase.mob.filecompactions.PartitionedMobFileCompactionRequest.CompactionPartition;
import org.apache.hadoop.hbase.mob.filecompactions.PartitionedMobFileCompactionRequest.CompactionPartitionId;
import org.apache.hadoop.hbase.regionserver.*;
import org.apache.hadoop.hbase.regionserver.StoreFile.Writer;
import org.apache.hadoop.hbase.regionserver.ScannerContext;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;

/**
 * An implementation of {@link MobFileCompactor} that compacts the mob files in partitions.
 */
@InterfaceAudience.Private
public class PartitionedMobFileCompactor extends MobFileCompactor {

  private static final Log LOG = LogFactory.getLog(PartitionedMobFileCompactor.class);
  protected long mergeableSize;
  protected int delFileMaxCount;
  /** The number of files compacted in a batch */
  protected int compactionBatchSize;
  protected int compactionKVMax;

  private Path tempPath;
  private Path bulkloadPath;
  private CacheConfig compactionCacheConfig;
  private Tag tableNameTag;

  public PartitionedMobFileCompactor(Configuration conf, FileSystem fs, TableName tableName,
    HColumnDescriptor column, ExecutorService pool) {
    super(conf, fs, tableName, column, pool);
    mergeableSize = conf.getLong(MobConstants.MOB_FILE_COMPACTION_MERGEABLE_THRESHOLD,
      MobConstants.DEFAULT_MOB_FILE_COMPACTION_MERGEABLE_THRESHOLD);
    delFileMaxCount = conf.getInt(MobConstants.MOB_DELFILE_MAX_COUNT,
      MobConstants.DEFAULT_MOB_DELFILE_MAX_COUNT);
    // default is 100
    compactionBatchSize = conf.getInt(MobConstants.MOB_FILE_COMPACTION_BATCH_SIZE,
      MobConstants.DEFAULT_MOB_FILE_COMPACTION_BATCH_SIZE);
    tempPath = new Path(MobUtils.getMobHome(conf), MobConstants.TEMP_DIR_NAME);
    bulkloadPath = new Path(tempPath, new Path(MobConstants.BULKLOAD_DIR_NAME, new Path(
      tableName.getNamespaceAsString(), tableName.getQualifierAsString())));
    compactionKVMax = this.conf.getInt(HConstants.COMPACTION_KV_MAX,
      HConstants.COMPACTION_KV_MAX_DEFAULT);
    Configuration copyOfConf = new Configuration(conf);
    copyOfConf.setFloat(HConstants.HFILE_BLOCK_CACHE_SIZE_KEY, 0f);
    compactionCacheConfig = new CacheConfig(copyOfConf);
    tableNameTag = new Tag(TagType.MOB_TABLE_NAME_TAG_TYPE, tableName.getName());
  }

  @Override
  public List<Path> compact(List<FileStatus> files, boolean isForceAllFiles) throws IOException {
    if (files == null || files.isEmpty()) {
      LOG.info("No candidate mob files");
      return null;
    }
    LOG.info("isForceAllFiles: " + isForceAllFiles);
    // find the files to compact.
    PartitionedMobFileCompactionRequest request = select(files, isForceAllFiles);
    // compact the files.
    return performCompaction(request);
  }

  /**
   * Selects the compacted mob/del files.
   * Iterates the candidates to find out all the del files and small mob files.
   * @param candidates All the candidates.
   * @param isForceAllFiles Whether add all mob files into the compaction.
   * @return A compaction request.
   * @throws IOException
   */
  protected PartitionedMobFileCompactionRequest select(List<FileStatus> candidates,
    boolean isForceAllFiles) throws IOException {
    Collection<FileStatus> allDelFiles = new ArrayList<FileStatus>();
    Map<CompactionPartitionId, CompactionPartition> filesToCompact =
      new HashMap<CompactionPartitionId, CompactionPartition>();
    final CompactionPartitionId id = new CompactionPartitionId();
    int selectedFileCount = 0;
    int irrelevantFileCount = 0;
    MobCompactPartitionPolicy policy = column.getMobCompactPartitionPolicy();

    Calendar calendar =  Calendar.getInstance();
    Date currentDate = new Date();
    Date firstDayOfCurrentMonth = null;
    Date firstDayOfCurrentWeek = null;

    if (policy == MobCompactPartitionPolicy.MONTHLY) {
      firstDayOfCurrentMonth = MobUtils.getFirstDayOfMonth(calendar, currentDate);
      firstDayOfCurrentWeek = MobUtils.getFirstDayOfWeek(calendar, currentDate);
    } else if (policy == MobCompactPartitionPolicy.WEEKLY) {
      firstDayOfCurrentWeek = MobUtils.getFirstDayOfWeek(calendar, currentDate);
    }

    for (FileStatus file : candidates) {
      if (!file.isFile()) {
        irrelevantFileCount++;
        continue;
      }
      // group the del files and small files.
      FileStatus linkedFile = file;
      if (HFileLink.isHFileLink(file.getPath())) {
        HFileLink link = HFileLink.buildFromHFileLinkPattern(conf, file.getPath());
        linkedFile = getLinkedFileStatus(link);
        if (linkedFile == null) {
          // If the linked file cannot be found, regard it as an irrelevantFileCount file
          irrelevantFileCount++;
          continue;
        }
      }
      if (StoreFileInfo.isDelFile(linkedFile.getPath())) {
        allDelFiles.add(file);
      } else {
        String fileName = linkedFile.getPath().getName();
        String date = MobFileName.getDateFromName(fileName);
        boolean skipCompaction = MobUtils.fillPartitionId(id, firstDayOfCurrentMonth,
            firstDayOfCurrentWeek, date, policy, calendar, mergeableSize);
        if (isForceAllFiles || (!skipCompaction && (linkedFile.getLen() < id.getThreshold()))) {
          // add all files if allFiles is true,
          // otherwise add the small files to the merge pool
          // filter out files which are not supposed to be compacted with the
          // current policy

          id.setStartKey(MobFileName.getStartKeyFromName(fileName));
          CompactionPartition compactionPartition = filesToCompact.get(id);
          if (compactionPartition == null) {
            CompactionPartitionId newId = new CompactionPartitionId(id.getStartKey(), id.getDate());
            compactionPartition = new CompactionPartition(newId);
            compactionPartition.addFile(file);
            filesToCompact.put(newId, compactionPartition);
            newId.updateLatestDate(date);
          } else {
            compactionPartition.addFile(file);
            compactionPartition.getPartitionId().updateLatestDate(date);
          }

          selectedFileCount++;
        }
      }
    }

    /*
     * If it is not a major mob compaction with del files, and the file number in Partition is 1,
     * remove the partition from filesToCompact list to avoid re-compacting files which has been
     * compacted with del files.
     */
    if (!isForceAllFiles && (allDelFiles.size() > 0)) {
      Iterator<Map.Entry<CompactionPartitionId, CompactionPartition>> it =
          filesToCompact.entrySet().iterator();

      while(it.hasNext()) {
        Map.Entry<CompactionPartitionId, CompactionPartition> entry = it.next();
        if (entry.getValue().getFileCount() == 1) {
          it.remove();
          --selectedFileCount;
        }
      }
    }

    PartitionedMobFileCompactionRequest request = new PartitionedMobFileCompactionRequest(
      filesToCompact.values(), allDelFiles);
    if (candidates.size() == (allDelFiles.size() + selectedFileCount + irrelevantFileCount)) {
      // all the files are selected
      request.setCompactionType(CompactionType.ALL_FILES);
    }
    LOG.info("The compaction type is " + request.getCompactionType() + ", the request has "
      + allDelFiles.size() + " del files, " + selectedFileCount + " selected files, and "
      + irrelevantFileCount + " irrelevant files");
    return request;
  }

  /**
   * Performs the compaction on the selected files.
   * <ol>
   * <li>Compacts the del files.</li>
   * <li>Compacts the selected small mob files and all the del files.</li>
   * <li>If all the candidates are selected, delete the del files.</li>
   * </ol>
   * @param request The compaction request.
   * @return The paths of new mob files generated in the compaction.
   * @throws IOException
   */
  protected List<Path> performCompaction(PartitionedMobFileCompactionRequest request)
    throws IOException {
    // merge the del files
    List<Path> delFilePaths = new ArrayList<Path>();
    for (FileStatus delFile : request.delFiles) {
      delFilePaths.add(delFile.getPath());
    }
    List<Path> newDelPaths = compactDelFiles(request, delFilePaths);
    List<StoreFile> newDelFiles = new ArrayList<StoreFile>();
    List<Path> paths = null;
    try {
      for (Path newDelPath : newDelPaths) {
        StoreFile sf = new StoreFile(fs, newDelPath, conf, compactionCacheConfig, BloomType.NONE);
        // pre-create reader of a del file to avoid race condition when opening the reader in each
        // partition.
        sf.createReader();
        newDelFiles.add(sf);
      }
      LOG.info("After merging, there are " + newDelFiles.size() + " del files");
      // compact the mob files by partitions.
      paths = compactMobFiles(request, newDelFiles);
      LOG.info("After compaction, there are " + paths.size() + " mob files");
    } finally {
      closeStoreFileReaders(newDelFiles);
    }
    // archive the del files if all the mob files are selected.
    if (request.type == CompactionType.ALL_FILES && !newDelPaths.isEmpty()) {
      LOG.info("After a mob file compaction with all files selected, archiving the del files "
        + newDelFiles);
      try {
        MobUtils.removeMobFiles(conf, fs, tableName, mobTableDir, column.getName(), newDelFiles);
      } catch (IOException e) {
        LOG.error("Failed to archive the del files " + newDelFiles, e);
      }
    }
    return paths;
  }

  /**
   * Compacts the selected small mob files and all the del files.
   * @param request The compaction request.
   * @param delFiles The del files.
   * @return The paths of new mob files after compactions.
   * @throws IOException
   */
  protected List<Path> compactMobFiles(final PartitionedMobFileCompactionRequest request,
    final List<StoreFile> delFiles) throws IOException {
    Collection<CompactionPartition> partitions = request.compactionPartitions;
    if (partitions == null || partitions.isEmpty()) {
      LOG.info("No partitions of mob files");
      return Collections.emptyList();
    }
    List<Path> paths = new ArrayList<Path>();
    final HTable table = new HTable(conf, tableName);
    try {
      Map<CompactionPartitionId, Future<List<Path>>> results =
        new HashMap<CompactionPartitionId, Future<List<Path>>>();
      // compact the mob files by partitions in parallel.
      for (final CompactionPartition partition : partitions) {
        results.put(partition.getPartitionId(), pool.submit(new Callable<List<Path>>() {
          @Override
          public List<Path> call() throws Exception {
            LOG.info("Compacting mob files for partition " + partition.getPartitionId());
            return compactMobFilePartition(request, partition, delFiles, table);
          }
        }));
      }
      // compact the partitions in parallel.
      boolean hasFailure = false;
      for (Entry<CompactionPartitionId, Future<List<Path>>> result : results.entrySet()) {
        try {
          paths.addAll(result.getValue().get());
        } catch (Exception e) {
          // just log the error
          LOG.error("Failed to compact the partition " + result.getKey(), e);
          hasFailure = true;
        }
      }
      if (hasFailure) {
        // if any partition fails in the compaction, directly throw an exception.
        throw new IOException("Failed to compact the partitions");
      }
    } finally {
      try {
        table.close();
      } catch (IOException e) {
        LOG.error("Failed to close the HTable", e);
      }
    }
    return paths;
  }

  /**
   * Compacts a partition of selected small mob files and all the del files.
   * @param request The compaction request.
   * @param partition A compaction partition.
   * @param delFiles The del files.
   * @param table The current table.
   * @return The paths of new mob files after compactions.
   * @throws IOException
   */
  private List<Path> compactMobFilePartition(PartitionedMobFileCompactionRequest request,
    CompactionPartition partition, List<StoreFile> delFiles, HTable table) throws IOException {
    List<Path> newFiles = new ArrayList<Path>();
    List<FileStatus> files = partition.listFiles();
    int offset = 0;
    Path bulkloadPathOfPartition = new Path(bulkloadPath, partition.getPartitionId().toString());
    Path bulkloadColumnPath = new Path(bulkloadPathOfPartition, column.getNameAsString());
    while (offset < files.size()) {
      int batch = compactionBatchSize;
      if (files.size() - offset < compactionBatchSize) {
        batch = files.size() - offset;
      }
      if (batch == 1 && delFiles.isEmpty()) {
        // only one file left and no del files, do not compact it,
        // and directly add it to the new files.
        newFiles.add(files.get(offset).getPath());
        offset++;
        continue;
      }
      // clean the bulkload directory to avoid loading old files.
      fs.delete(bulkloadPathOfPartition, true);
      // add the selected mob files and del files into filesToCompact
      List<StoreFile> filesToCompact = new ArrayList<StoreFile>();
      for (int i = offset; i < batch + offset; i++) {
        StoreFile sf = new StoreFile(fs, files.get(i).getPath(), conf, compactionCacheConfig,
          BloomType.NONE);
        filesToCompact.add(sf);
      }
      filesToCompact.addAll(delFiles);
      // compact the mob files in a batch.
      compactMobFilesInBatch(request, partition, table, filesToCompact, batch,
        bulkloadPathOfPartition, bulkloadColumnPath, newFiles);
      // move to the next batch.
      offset += batch;
    }
    LOG.info("Compaction is finished. The number of mob files is changed from " + files.size()
      + " to " + newFiles.size());
    return newFiles;
  }

  /**
   * Closes the readers of store files.
   * @param storeFiles The store files to be closed.
   */
  private void closeStoreFileReaders(List<StoreFile> storeFiles) {
    for (StoreFile storeFile : storeFiles) {
      try {
        storeFile.closeReader(true);
      } catch (IOException e) {
        LOG.warn("Failed to close the reader on store file " + storeFile.getPath(), e);
      }
    }
  }

  /**
   * Compacts a partition of selected small mob files and all the del files in a batch.
   * @param request The compaction request.
   * @param partition A compaction partition.
   * @param table The current table.
   * @param filesToCompact The files to be compacted.
   * @param batch The number of mob files to be compacted in a batch.
   * @param bulkloadPathOfPartition The directory where the bulkload column of the current
   *        partition is saved.
   * @param bulkloadColumnPath The directory where the bulkload files of current partition
   *        are saved.
   * @param newFiles The paths of new mob files after compactions.
   * @throws IOException
   */
  private void compactMobFilesInBatch(PartitionedMobFileCompactionRequest request,
    CompactionPartition partition, HTable table, List<StoreFile> filesToCompact, int batch,
    Path bulkloadPathOfPartition, Path bulkloadColumnPath, List<Path> newFiles)
    throws IOException {
    // open scanner to the selected mob files and del files.
    StoreScanner scanner = createScanner(filesToCompact, ScanType.COMPACT_DROP_DELETES);
    // the mob files to be compacted, not include the del files.
    List<StoreFile> mobFilesToCompact = filesToCompact.subList(0, batch);
    // Pair(maxSeqId, cellsCount)
    Pair<Long, Long> fileInfo = getFileInfo(mobFilesToCompact);
    // open writers for the mob files and new ref store files.
    Writer writer = null;
    Writer refFileWriter = null;
    Path filePath = null;
    long mobCells = 0;
    boolean cleanupTmpMobFile = false;
    boolean cleanupBulkloadDirOfPartition = false;
    boolean cleanupCommittedMobFile = false;
    boolean closeReaders= true;

    try {
      try {
        writer = MobUtils
            .createWriter(conf, fs, column, partition.getPartitionId().getLatestDate(), tempPath,
                Long.MAX_VALUE, column.getCompactionCompressionType(),
                partition.getPartitionId().getStartKey(), compactionCacheConfig);
        cleanupTmpMobFile = true;
        filePath = writer.getPath();
        byte[] fileName = Bytes.toBytes(filePath.getName());
        // create a temp file and open a writer for it in the bulkloadPath
        refFileWriter = MobUtils.createRefFileWriter(conf, fs, column, bulkloadColumnPath, fileInfo
          .getSecond().longValue(), compactionCacheConfig);
        cleanupBulkloadDirOfPartition = true;
        List<Cell> cells = new ArrayList<Cell>();
        boolean hasMore = false;
        ScannerContext scannerContext =
          ScannerContext.newBuilder().setBatchLimit(compactionKVMax).build();
        do {
          hasMore = scanner.next(cells, scannerContext);
          for (Cell cell : cells) {
            // TODO remove this after the new code are introduced.
            KeyValue kv = KeyValueUtil.ensureKeyValue(cell);
            // write the mob cell to the mob file.
            writer.append(kv);
            // write the new reference cell to the store file.
            KeyValue reference = MobUtils.createMobRefKeyValue(kv, fileName, tableNameTag);
            refFileWriter.append(reference);
            mobCells++;
          }
          cells.clear();
        } while (hasMore);
      } finally {
        // close the scanner.
        scanner.close();

        if (cleanupTmpMobFile) {
          // append metadata to the mob file, and close the mob file writer.
          closeMobFileWriter(writer, fileInfo.getFirst(), mobCells);
        }

        if (cleanupBulkloadDirOfPartition) {
          // append metadata and bulkload info to the ref mob file, and close the writer.
          closeRefFileWriter(refFileWriter, fileInfo.getFirst(), request.selectionTime);
        }
      }

      if (mobCells > 0) {
        // commit mob file
        MobUtils.commitFile(conf, fs, filePath, mobFamilyDir, compactionCacheConfig);
        cleanupTmpMobFile = false;
        cleanupCommittedMobFile = true;
        // bulkload the ref file
        bulkloadRefFile(table, bulkloadPathOfPartition, filePath.getName());
        cleanupCommittedMobFile = false;
        newFiles.add(new Path(mobFamilyDir, filePath.getName()));
      }

      // archive the old mob files, do not archive the del files.
      try {
        closeStoreFileReaders(mobFilesToCompact);
        closeReaders = false;
        MobUtils.removeMobFiles(conf, fs, tableName, mobTableDir, column.getName(), mobFilesToCompact);
      } catch (IOException e) {
        LOG.error("Failed to archive the files " + mobFilesToCompact, e);
      }
    } finally {
      if (closeReaders) {
        closeStoreFileReaders(mobFilesToCompact);
      }

      if (cleanupTmpMobFile) {
        deletePath(filePath);
      }

      if (cleanupBulkloadDirOfPartition) {
        // delete the bulkload files in bulkloadPath
        deletePath(bulkloadPathOfPartition);
      }

      if (cleanupCommittedMobFile) {
        deletePath(new Path(mobFamilyDir, filePath.getName()));
      }
    }
  }

  /**
   * Compacts the del files in batches which avoids opening too many files.
   * @param request The compaction request.
   * @param delFilePaths
   * @return The paths of new del files after merging or the original files if no merging
   *         is necessary.
   * @throws IOException
   */
  protected List<Path> compactDelFiles(PartitionedMobFileCompactionRequest request,
    List<Path> delFilePaths) throws IOException {
    if (delFilePaths.size() <= delFileMaxCount) {
      return delFilePaths;
    }
    // when there are more del files than the number that is allowed, merge it firstly.
    int offset = 0;
    List<Path> paths = new ArrayList<Path>();
    while (offset < delFilePaths.size()) {
      // get the batch
      int batch = compactionBatchSize;
      if (delFilePaths.size() - offset < compactionBatchSize) {
        batch = delFilePaths.size() - offset;
      }
      List<StoreFile> batchedDelFiles = new ArrayList<StoreFile>();
      if (batch == 1) {
        // only one file left, do not compact it, directly add it to the new files.
        paths.add(delFilePaths.get(offset));
        offset++;
        continue;
      }
      for (int i = offset; i < batch + offset; i++) {
        batchedDelFiles.add(new StoreFile(fs, delFilePaths.get(i), conf, compactionCacheConfig,
          BloomType.NONE));
      }
      // compact the del files in a batch.
      paths.add(compactDelFilesInBatch(request, batchedDelFiles));
      // move to the next batch.
      offset += batch;
    }
    return compactDelFiles(request, paths);
  }

  /**
   * Compacts the del file in a batch.
   * @param request The compaction request.
   * @param delFiles The del files.
   * @return The path of new del file after merging.
   * @throws IOException
   */
  private Path compactDelFilesInBatch(PartitionedMobFileCompactionRequest request,
    List<StoreFile> delFiles) throws IOException {
    // create a scanner for the del files.
    StoreScanner scanner = createScanner(delFiles, ScanType.COMPACT_RETAIN_DELETES);
    Writer writer = null;
    Path filePath = null;
    try {
      writer = MobUtils.createDelFileWriter(conf, fs, column,
        MobUtils.formatDate(new Date(request.selectionTime)), tempPath, Long.MAX_VALUE,
        column.getCompactionCompression(), HConstants.EMPTY_START_ROW, compactionCacheConfig);
      filePath = writer.getPath();
      List<Cell> cells = new ArrayList<Cell>();
      boolean hasMore = false;
      ScannerContext scannerContext =
          ScannerContext.newBuilder().setBatchLimit(compactionKVMax).build();
      do {
        hasMore = scanner.next(cells, scannerContext);
        for (Cell cell : cells) {
          // TODO remove this after the new code are introduced.
          KeyValue kv = KeyValueUtil.ensureKeyValue(cell);
          writer.append(kv);
        }
        cells.clear();
      } while (hasMore);
    } finally {
      scanner.close();
      if (writer != null) {
        try {
          writer.close();
        } catch (IOException e) {
          LOG.error("Failed to close the writer of the file " + filePath, e);
        }
      }
    }
    // commit the new del file
    Path path = MobUtils.commitFile(conf, fs, filePath, mobFamilyDir, compactionCacheConfig);
    // archive the old del files
    try {
      MobUtils.removeMobFiles(conf, fs, tableName, mobTableDir, column.getName(), delFiles);
    } catch (IOException e) {
      LOG.error("Failed to archive the old del files " + delFiles, e);
    }
    return path;
  }

  /**
   * Creates a store scanner.
   * @param filesToCompact The files to be compacted.
   * @param scanType The scan type.
   * @return The store scanner.
   * @throws IOException
   */
  private StoreScanner createScanner(List<StoreFile> filesToCompact, ScanType scanType)
    throws IOException {
    List scanners = StoreFileScanner.getScannersForStoreFiles(filesToCompact, false, true, false,
      false, HConstants.LATEST_TIMESTAMP);
    Scan scan = new Scan();
    scan.setMaxVersions(column.getMaxVersions());
    long ttl = HStore.determineTTLFromFamily(column);
    ScanInfo scanInfo = new ScanInfo(conf, column, ttl, 0, KeyValue.COMPARATOR);
    StoreScanner scanner = new StoreScanner(scan, scanInfo, scanType, null, scanners, 0L,
      HConstants.LATEST_TIMESTAMP);
    return scanner;
  }

  /**
   * Bulkloads the current file.
   * @param table The current table.
   * @param bulkloadDirectory The path of bulkload directory.
   * @param fileName The current file name.
   * @throws IOException
   */
  private void bulkloadRefFile(HTable table, Path bulkloadDirectory, String fileName)
    throws IOException {
    // bulkload the ref file
    try {
      LoadIncrementalHFiles bulkload = new LoadIncrementalHFiles(conf);
      bulkload.doBulkLoad(bulkloadDirectory, table);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  /**
   * Closes the mob file writer.
   * @param writer The mob file writer.
   * @param maxSeqId Maximum sequence id.
   * @param mobCellsCount The number of mob cells.
   * @throws IOException
   */
  private void closeMobFileWriter(Writer writer, long maxSeqId, long mobCellsCount)
    throws IOException {
    if (writer != null) {
      writer.appendMetadata(maxSeqId, false, mobCellsCount);
      try {
        writer.close();
      } catch (IOException e) {
        LOG.error("Failed to close the writer of the file " + writer.getPath(), e);
      }
    }
  }

  /**
   * Closes the ref file writer.
   * @param writer The ref file writer.
   * @param maxSeqId Maximum sequence id.
   * @param bulkloadTime The timestamp at which the bulk load file is created.
   * @throws IOException
   */
  private void closeRefFileWriter(Writer writer, long maxSeqId, long bulkloadTime)
    throws IOException {
    if (writer != null) {
      writer.appendMetadata(maxSeqId, false);
      writer.appendFileInfo(StoreFile.BULKLOAD_TIME_KEY, Bytes.toBytes(bulkloadTime));
      try {
        writer.close();
      } catch (IOException e) {
        LOG.error("Failed to close the writer of the ref file " + writer.getPath(), e);
      }
    }
  }

  /**
   * Gets the max seqId and number of cells of the store files.
   * @param storeFiles The store files.
   * @return The pair of the max seqId and number of cells of the store files.
   * @throws IOException
   */
  private Pair<Long, Long> getFileInfo(List<StoreFile> storeFiles) throws IOException {
    long maxSeqId = 0;
    long maxKeyCount = 0;
    for (StoreFile sf : storeFiles) {
      // the readers will be closed later after the merge.
      maxSeqId = Math.max(maxSeqId, sf.getMaxSequenceId());
      byte[] count = sf.createReader().loadFileInfo().get(StoreFile.MOB_CELLS_COUNT);
      if (count != null) {
        maxKeyCount += Bytes.toLong(count);
      }
    }
    return new Pair<Long, Long>(Long.valueOf(maxSeqId), Long.valueOf(maxKeyCount));
  }

  /**
   * Deletes a file.
   * @param path The path of the file to be deleted.
   */
  private void deletePath(Path path) {
    try {
      if (path != null) {
        fs.delete(path, true);
      }
    } catch (IOException e) {
      LOG.error("Failed to delete the file " + path, e);
    }
  }

  private FileStatus getLinkedFileStatus(HFileLink link) throws IOException {
    Path[] locations = link.getLocations();
    for (Path location : locations) {
      FileStatus file = getFileStatus(location);
      if (file != null) {
        return file;
      }
    }
    return null;
  }

  private FileStatus getFileStatus(Path path) throws IOException {
    try {
      if (path != null) {
        FileStatus file = fs.getFileStatus(path);
        return file;
      }
    } catch (FileNotFoundException e) {
      LOG.warn("The file " + path + " can not be found", e);
    }
    return null;
  }
}
