package org.endeavourhealth.sftpreader.implementations.emis;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.endeavourhealth.sftpreader.DataLayer;
import org.endeavourhealth.sftpreader.implementations.SftpBatchSplitter;
import org.endeavourhealth.sftpreader.model.db.*;
import org.endeavourhealth.sftpreader.model.exceptions.SftpFilenameParseException;
import org.endeavourhealth.sftpreader.utilities.CsvJoiner;
import org.endeavourhealth.sftpreader.utilities.CsvSplitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class EmisSftpBatchSplitter extends SftpBatchSplitter {

    private static final Logger LOG = LoggerFactory.getLogger(EmisSftpBatchSplitter.class);

    private static final String SPLIT_COLUMN_ORG = "OrganisationGuid";
    private static final String SPLIT_COLUMN_PROCESSING_ID = "ProcessingId";

    private static final String SPLIT_FOLDER = "Split";

    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT;

    /**
     * splits the EMIS extract files we use by org GUID and processing ID, so
     * we have a directory structure of dstDir -> org GUID -> processing ID
     * returns a list of directories containing split file sets
     */
    @Override
    public List<BatchSplit> splitBatch(Batch batch, DataLayer db, DbConfiguration dbConfiguration) throws Exception {

        String fullLocalRootPath = dbConfiguration.getFullLocalRootPath();

        String path = FilenameUtils.concat(fullLocalRootPath, batch.getLocalRelativePath());
        File srcDir = new File(path);

        LOG.trace("Splitting CSV files in {}", srcDir);

        if (!srcDir.exists()) {
            throw new FileNotFoundException("Source directory " + srcDir + " doesn't exist");
        }

        //split into a sub-folder called "split"
        path = FilenameUtils.concat(path, SPLIT_FOLDER);
        File dstDir = new File(path);

        //if the folder does exist, delete all content within it, since if we're re-splitting a file
        //we want to make sure that all previous content is deleted
        if (dstDir.exists()
            || dstDir.listFiles() != null) { //listing the files is a workaround for the NFS inaccurately reporting if something exists
            deleteRecursive(dstDir);
        }

        if (!dstDir.mkdirs()) {
            throw new FileNotFoundException("Failed to create destination directory " + dstDir);
        }

        //scan through the files in the folder and works out which are admin and which are clinical
        List<String> processingIdFiles = new ArrayList<>();
        List<String> orgAndProcessingIdFiles = new ArrayList<>();
        List<String> orgIdFiles = new ArrayList<>();
        identifyFiles(batch, srcDir, orgAndProcessingIdFiles, processingIdFiles, orgIdFiles, dbConfiguration);

        //split the org ID-only files so we have a directory per organisation ID
        for (String fileName: orgIdFiles) {
            File f = new File(srcDir, fileName);
            LOG.trace("Splitting {} into {}", f, dstDir);
            splitFile(f, dstDir, CSV_FORMAT, SPLIT_COLUMN_ORG);
        }

        //splitting the sharing agreements file will have created a folder for every org listed,
        //including the non-active ones in there. So delete any folder for orgs that aren't active in the
        //sharing agreement
        Set<File> expectedOrgFolders = findExpectedOrgFolders(dstDir, fullLocalRootPath, batch);
        for (File orgDir: dstDir.listFiles()) {
            if (!expectedOrgFolders.contains(orgDir)) {
                deleteRecursive(orgDir);
            }
        }

        //split the clinical files by org and processing ID, which creates the org ID -> processing ID folder structure
        for (String fileName: orgAndProcessingIdFiles) {
            File f = new File(srcDir, fileName);
            LOG.trace("Splitting {} into {}", f, dstDir);
            Set<File> splitFiles = splitFile(f, dstDir, CSV_FORMAT, SPLIT_COLUMN_ORG, SPLIT_COLUMN_PROCESSING_ID);

            //having split the file, we then want to join the files back together so we have one per
            //organisation but ordered by processing ID
            for (File orgDir: dstDir.listFiles()) {
                joinFiles(fileName, orgDir, splitFiles);
            }
        }

        //for the files with just a processing ID, each org folder we want a copy of the non-clinical data, but in processing ID order
        for (String fileName : processingIdFiles) {
            File f = new File(srcDir, fileName);

            File reorderedFile = null;

            for (File orgDir: dstDir.listFiles()) {

                //if we've not split and re-ordered the file, do it now into this org dir
                if (reorderedFile == null) {
                    LOG.trace("Splitting {} into {}", f, orgDir);
                    Set<File> splitFiles = splitFile(f, orgDir, CSV_FORMAT, SPLIT_COLUMN_PROCESSING_ID);

                    //join them back together
                    reorderedFile = joinFiles(fileName, orgDir, splitFiles);

                    //if the file was empty, there won't be a reordered file, so just drop out, and let the
                    //thing that creates empty files pick this up
                    if (reorderedFile == null) {
                        break;
                    }

                } else {
                    //if we have split and re-ordered the file, just copy it into this org dir
                    File orgFile = new File(orgDir, fileName);
                    Files.copy(reorderedFile.toPath(), orgFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        //each org dir with have loads of empty folders for the processing IDs, so delete them
        for (File orgDir : dstDir.listFiles()) {

            for (File f: orgDir.listFiles()) {
                if (f.isDirectory()
                        && f.listFiles().length == 0) {
                    deleteRecursive(f);
                }
            }

            //the sharing agreements file always has a row per org in the data sharing agreement, even if there
            //isn't any data for that org in the extract. So we'll have just created a folder for that org
            //and it'll now be empty. So delete any empty org directory.
            if (orgDir.listFiles().length == 0) {
                deleteRecursive(orgDir);
            }
        }

        //the splitter only creates files when required, so we'll have incomplete file sets,
        //so create any missing files, so there's a full set of files in every folder
        for (BatchFile batchFile: batch.getBatchFiles()) {
            File f = new File(srcDir, batchFile.getDecryptedFilename());
            createMissingFiles(f, dstDir);
        }

        //if any of our ORG folders contains child folders, then something has gone wrong with the splitting and joining,
        //so check for this and throw an exception if this is the case
        validateSplitFolders(srcDir, dstDir);

        LOG.trace("Completed CSV file splitting from {} to {}", srcDir, dstDir);

        //we need to parse the organisation file, to store the mappings for later
        saveAllOdsCodes(db, fullLocalRootPath, batch);

        //build a list of the folders containing file sets, to return
        List<BatchSplit> ret = new ArrayList<>();

        for (File orgDir : dstDir.listFiles()) {

            String orgGuid = orgDir.getName();
            String localPath = FilenameUtils.concat(batch.getLocalRelativePath(), SPLIT_FOLDER);
            localPath = FilenameUtils.concat(localPath, orgGuid);

            //we need to find the ODS code for the EMIS org GUID. When we have a full extract, we can find that mapping
            //in the Organisation CSV file, but for deltas, we use the key-value-pair table which is populated when we get the deltas
            String odsCode = findOdsCode(orgGuid, db);

            BatchSplit batchSplit = new BatchSplit();
            batchSplit.setBatchId(batch.getBatchId());
            batchSplit.setLocalRelativePath(localPath);
            batchSplit.setOrganisationId(odsCode);

            ret.add(batchSplit);
        }

        return ret;
    }

    /**
     * validates that each org dir contains no sub-directories, that every folder contains the same number of files
     * and that the total size of the split files is larger than the original (it'll be larger due to duplication
     * of CSV header lines and that some files aren't split by org)
     */
    private void validateSplitFolders(File srcDir, File dstDir) throws Exception {

        Map<String, Long> fileNamesAndSizes = new HashMap<>();

        for (File orgDir : dstDir.listFiles()) {

            File[] orgDirContents = orgDir.listFiles();
            if (!fileNamesAndSizes.isEmpty()
                && fileNamesAndSizes.size() != orgDirContents.length) {
                throw new Exception("Organisation dir " + orgDir + " contains " + orgDirContents.length + " but others contain " + fileNamesAndSizes.size());
            }

            for (File orgDirChild: orgDirContents) {

                if (orgDirChild.isDirectory()) {
                    throw new Exception("Organisation dir " + orgDir + " should not contain subdirectories (" + orgDirChild.getName() + ")");
                }

                String name = orgDirChild.getName();
                long size = orgDirChild.length();

                Long totalSize = fileNamesAndSizes.get(name);
                if (totalSize == null) {
                    fileNamesAndSizes.put(name, new Long(size));

                } else {
                    fileNamesAndSizes.put(name, new Long(size + totalSize.longValue()));
                }
            }
        }

        //backed out this check, since in some cases the split files do end up smaller than the original one,
        //because emis send duplicate rows (at least in the sessionUser file) which the splitter ignores
        /*for (String fileName: fileNamesAndSizes.keySet()) {
            Long totalSize = fileNamesAndSizes.get(fileName);

            File srcFile = new File(srcDir, fileName);
            long srcSize = srcFile.length();

            if (totalSize.longValue() < srcSize) {
                throw new Exception("Split " + fileName + " files add up to less than the original size");
            }
        }*/
    }

    private static File joinFiles(String fileName, File directory, Set<File> splitFiles) throws Exception {
        File joinedFile = new File(directory, fileName);

        List<File> separateFiles = new ArrayList<>();

        for (File orgProcessingIdDir: findDirectoriesAndOrderByNumber(directory)) {
            //there may not be a split file with our name in each of the folders,
            //so check the split files set, to see if there was one created during the split
            File orgProcessingIdFile = new File(orgProcessingIdDir, fileName);
            if (splitFiles.contains(orgProcessingIdFile)) {
                separateFiles.add(orgProcessingIdFile);
            }
        }

        CsvJoiner joiner = new CsvJoiner(separateFiles, joinedFile, CSV_FORMAT);
        boolean joined = joiner.go();

        //delete all the separate files
        for (File orgProcessingIdFile: separateFiles) {
            deleteRecursive(orgProcessingIdFile);
        }

        if (joined) {
            return joinedFile;
        } else {
            return null;
        }
    }

    private static List<File> findDirectoriesAndOrderByNumber(File rootDir) {

        //the org directory contains a sub-directory for each processing ID, which must be processed in order
        List<Integer> processingIds = new ArrayList<>();
        Map<Integer, File> hmFiles = new HashMap<>();

        for (File file: rootDir.listFiles()) {
            if (file.isDirectory()) {
                Integer processingId = Integer.valueOf(file.getName());
                processingIds.add(processingId);
                hmFiles.put(processingId, file);
            }
        }

        Collections.sort(processingIds);

        List<File> ret = new ArrayList<>();

        for (Integer processingId: processingIds) {
            File f = hmFiles.get(processingId);
            ret.add(f);
        }

        return ret;
    }

    /**
     * goes through the sharing agreements file to find the org GUIDs of those orgs activated in the sharing agreement
     */
    private Set<File> findExpectedOrgFolders(File dstDir, String fullLocalInstancePath, Batch batch) throws Exception {

        File sharingAgreementFile = null;
        for (BatchFile batchFile: batch.getBatchFiles()) {
            if (batchFile.getFileTypeIdentifier().equalsIgnoreCase("Agreements_SharingOrganisation")) {
                String path = FilenameUtils.concat(fullLocalInstancePath, batch.getLocalRelativePath());
                path = FilenameUtils.concat(path, batchFile.getDecryptedFilename());
                sharingAgreementFile = new File(path);
                break;
            }
        }

        Set<File> ret = new HashSet<>();

        CSVParser csvParser = CSVParser.parse(sharingAgreementFile, Charset.defaultCharset(), CSV_FORMAT.withHeader());
        try {
            Iterator<CSVRecord> csvIterator = csvParser.iterator();

            while (csvIterator.hasNext()) {
                CSVRecord csvRecord = csvIterator.next();

                String orgGuid = csvRecord.get("OrganisationGuid");
                String activated = csvRecord.get("IsActivated");
                if (activated.equalsIgnoreCase("true")) {

                    File orgDir = new File(dstDir, orgGuid);
                    ret.add(orgDir);
                }
            }
        } finally {
            csvParser.close();
        }

        return ret;
    }

    /**
     * file.delete() only works on empty directories, so we need to make sure they're empty first
     */
    private static void deleteRecursive(File f) throws Exception {
        if (f.isDirectory()) {
            for (File child: f.listFiles()) {
                deleteRecursive(child);
            }
        }
        if (!f.delete()) {
            throw new IOException("Failed to delete " + f);
        }
    }

    private static void saveAllOdsCodes(DataLayer db, String fullLocalInstancePath, Batch batch) throws Exception {

        //go through our Admin_Organisation file, saving all new org details to our PostgreSQL DB
        File adminCsvFile = null;
        for (BatchFile batchFile: batch.getBatchFiles()) {
            if (batchFile.getFileTypeIdentifier().equalsIgnoreCase("Admin_Organisation")) {
                String path = FilenameUtils.concat(fullLocalInstancePath, batch.getLocalRelativePath());
                path = FilenameUtils.concat(path, batchFile.getDecryptedFilename());
                adminCsvFile = new File(path);
            }
        }

        CSVParser csvParser = CSVParser.parse(adminCsvFile, Charset.defaultCharset(), CSV_FORMAT.withHeader());
        try {
            Iterator<CSVRecord> csvIterator = csvParser.iterator();

            while (csvIterator.hasNext()) {
                CSVRecord csvRecord = csvIterator.next();

                String orgGuid = csvRecord.get("OrganisationGuid");
                String orgName = csvRecord.get("OrganisationName");
                String orgOds = csvRecord.get("ODSCode");

                if (StringUtils.isNotEmpty(orgOds)) {
                    EmisOrganisationMap mapping = new EmisOrganisationMap()
                            .setGuid(orgGuid)
                            .setName(orgName)
                            .setOdsCode(orgOds);

                    db.addEmisOrganisationMap(mapping);
                }
            }
        } finally {
            csvParser.close();
        }
    }

    private static String findOdsCode(String emisOrgGuid, DataLayer db) throws Exception {

        //look in our mapping table to find the ODS code for our org GUID
        EmisOrganisationMap mapping = db.getEmisOrganisationMap(emisOrgGuid);
        if (mapping != null) {
            return mapping.getOdsCode();
        }

        throw new RuntimeException("Failed to find ODS code for EMIS Org GUID " + emisOrgGuid);
    }

    /**
     * scans through the files in the folder and works out which are admin and which are clinical
     */
    private static void identifyFiles(Batch batch, File srcDir, List<String> orgAndProcessingIdFiles, List<String> processingIdFiles,
                                      List<String> orgIdFiles, DbConfiguration dbConfiguration) throws Exception {

        for (BatchFile batchFile: batch.getBatchFiles()) {

            String fileName = batchFile.getDecryptedFilename();
            EmisSftpFilenameParser nameParser = new EmisSftpFilenameParser(fileName, dbConfiguration, ".csv");
            String fileType = nameParser.generateFileTypeIdentifier();

            //we work out what columns to split by, by looking at the CSV file headers
            File f = new File(srcDir, fileName);
            CSVParser csvParser = CSVParser.parse(f, Charset.defaultCharset(), CSV_FORMAT.withHeader());
            Map<String, Integer> headers = csvParser.getHeaderMap();
            csvParser.close();

            boolean splitByOrgId = headers.containsKey(SPLIT_COLUMN_ORG)
                    && !fileType.equals("Admin_Organisation") //these three files have an OrganisationGuid column, but don't want splitting by it
                    && !fileType.equals("Admin_OrganisationLocation")
                    && !fileType.equals("Admin_UserInRole");

            boolean splitByProcessingId = headers.containsKey(SPLIT_COLUMN_PROCESSING_ID);

            if (splitByOrgId && splitByProcessingId) {
                orgAndProcessingIdFiles.add(fileName);

            } else if (splitByOrgId) {
                orgIdFiles.add(fileName);

            } else if (splitByProcessingId) {
                processingIdFiles.add(fileName);

            } else {
                throw new SftpFilenameParseException("Unknown EMIS CSV file type for " + fileName);
            }
        }
    }

    private static void createMissingFiles(File srcFile, File dstDir) throws Exception {

        //read in the first line of the source file, as we use that as the content for the empty files
        String headers = readFileHeaders(srcFile);
        String fileName = srcFile.getName();

        //iterate through any directories, creating any missing files in their sub-directories
        for (File orgDir: dstDir.listFiles()) {
            if (orgDir.isDirectory()) {
                createMissingFile(fileName, headers, orgDir);
            }
        }
    }

    private static void createMissingFile(String fileName, String headers, File dstDir) throws Exception {

        File dstFile = new File(dstDir, fileName);
        //TODO - fix this
        if (dstFile.exists()) {
            return;
        }

        FileWriter fileWriter = null;
        BufferedWriter bufferedWriter = null;

        try {

            fileWriter = new FileWriter(dstFile);
            bufferedWriter = new BufferedWriter(fileWriter);
            bufferedWriter.append(headers);
            bufferedWriter.newLine();

        } finally {

            if (bufferedWriter != null) {
                bufferedWriter.close();
            }
            if (fileWriter != null) {
                fileWriter.close();
            }
        }
    }

    private static String readFileHeaders(File srcFile) throws Exception {

        String headers = null;
        BufferedReader bufferedReader = null;
        try {
            bufferedReader = new BufferedReader(new FileReader(srcFile));
            headers = bufferedReader.readLine();
        } finally {
            if (bufferedReader != null) {
                bufferedReader.close();
            }
        }
        return headers;
    }

    private static Set<File> splitFile(File srcFile, File dstDir, CSVFormat csvFormat, String... splitColmumns) throws Exception {
        CsvSplitter csvSplitter = new CsvSplitter(srcFile, dstDir, csvFormat, splitColmumns);
        return csvSplitter.go();
    }
}
