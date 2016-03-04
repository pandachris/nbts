package netbeanstypescript;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.modules.parsing.api.Snapshot;
import org.netbeans.modules.parsing.api.Source;
import org.netbeans.modules.parsing.spi.indexing.Context;
import org.openide.filesystems.FileLock;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileSystem;
import org.openide.filesystems.FileUtil;
import org.openide.util.Exceptions;

/**
 *
 * @author pandachris $Id: $
 */
public class TSExtUtil {

    private static final Logger LOGGER = Logger.getLogger(TSExtUtil.class.getName());

    private static final String TSCONFIG_FILENAME = "tsconfig.json";
    private static final String NODE_MODULES_DIRNAME = "node_modules";
    private static final String TYPINGS_FILENAME = "typings.json";
    private static final String TYPINGS_DIRNAME = "typings";
    private static final List<String> REQUIRED_FILES = Arrays.asList(new String[]{
        TSCONFIG_FILENAME, NODE_MODULES_DIRNAME, TYPINGS_FILENAME, TYPINGS_DIRNAME});

    /**
     * the source context related to this util instance.
     */
    private final Context context;
    /**
     * project instance related to this context.
     */
    private final Project project;
    /**
     *      */
    private final String contextRootPath;
    private final Map<String, FileObject> requiredFileObjects = new HashMap<>();
    private final Map<String, FileObject> extRelPaths = new HashMap<>();
    private JSONObject tsConfig = null;
    private FileObject tsSourceRoot;
    private final Map<String, FileObject> extFiles = new HashMap<>();

    /**
     * Location of the TS source files (as determined by tsconfig.json) relative to the root of the source context. If this is null then we can ignore this
     * context, as it does not contain our TS sources. When this is not null, it will be the virtual path we give to files (like node_modules) which may be
     * located outside the source context.
     */
    private String tsRootRelPath = null;

    public TSExtUtil(Context context) {
        this.context = context;
        this.contextRootPath = context.getRoot().getPath();
        project = FileOwnerQuery.getOwner(context.getRoot());
        if (project != null) {
            scanProject();
        }
    }

    private void scanProject() {
        locateRequiredFiles();
        getTsSourceRoot();
        if (tsSourceRoot != null) {
            // determine whether the TS source root is within our source context
            tsRootRelPath = FileUtil.getRelativePath(context.getRoot(), tsSourceRoot);
        }
    }

    public boolean isTsContext() {
        return tsRootRelPath != null;
    }

    public boolean isFileExternal(String path) {
        return extFiles.containsKey(path);
    }

    /**
     * Try to find all of the files listed in the REQUIRED_FILES array. First, search the source context, then search at the project root dir for anything not
     * found in the source context.
     *
     * @param filePath
     * @return
     */
    private void locateRequiredFiles() {
        Map<String, FileObject> filesFound = new HashMap<>();
        // first search in the source context for the files we need
        List<String> missingFiles = new ArrayList<>(REQUIRED_FILES);
        findFilesInContext(filesFound, missingFiles, context.getRoot());
        requiredFileObjects.putAll(filesFound);
        missingFiles.removeAll(filesFound.keySet());
        if (!missingFiles.isEmpty()) {
            // file(s) not found in src context: look at the project level
            filesFound.clear();
            findFilesAboveContext(filesFound, missingFiles, context.getRoot().getParent());
            requiredFileObjects.putAll(filesFound);
            missingFiles.removeAll(filesFound.keySet());
            extRelPaths.putAll(filesFound);
            if (!missingFiles.isEmpty()) {
                LOGGER.log(Level.SEVERE, "Required files not found in project: {0}", missingFiles);
            }
        }
    }

    /**
     * Look for the required files to exist inside dir. Will search recursively in depth, will only traverse the tree once, will stop when all files are found.
     *
     * @param filesFound Map contains the files that have been located
     * @param filesNeeded Files to be found. Will accept relative paths like subdir/fileName.ext, or just file names.
     * @param dir Place to look (recursive).
     * @param ignoreSourceContext If true, the algorithm will ignore the folder of the source context. This is probably because it has been searched already,
     * don't want to waste time searching it again.
     * @return The fileObject if found, or null if not found.
     */
    private void findFilesInContext(Map<String, FileObject> filesFound, Collection<String> filesNeeded, FileObject dir) {
        if (filesNeeded.isEmpty() || dir == null || !dir.isFolder()) {
            return;
        }
        for (String fileName : filesNeeded) {
            if (filesFound.containsKey(fileName)) {
                continue;
            }
            FileObject target = dir.getFileObject(fileName);
            if (target != null) {
                filesFound.put(fileName, target);
                LOGGER.log(Level.INFO, "Required TS project file found: {0}", target.getPath());
            }
        }
        if (!filesNeeded.isEmpty()) {
            Enumeration<? extends FileObject> children = dir.getFolders(false);
            while (children.hasMoreElements() && !filesNeeded.isEmpty()) {
                findFilesInContext(filesFound, filesNeeded, children.nextElement());
            }
        }
    }

    /**
     * Search for files as 'uncles' to the context root -- look for them to be siblings to the parent chain of the context root, but don't look outside the
     * project root.
     *
     * @param filesFound Populated as files are found.
     * @param filesNeeded Collection of the files we need to find.
     * @param dir start at the parent dir of the context root. Will recursively walk up to the project root until all files are found.
     */
    private void findFilesAboveContext(Map<String, FileObject> filesFound, Collection<String> filesNeeded, FileObject dir) {
        if (filesFound.keySet().containsAll(filesNeeded) || FileUtil.getRelativePath(project.getProjectDirectory(), dir) == null) {
            return;
        }
        for (FileObject child : dir.getChildren()) {
            String childName = child.getNameExt();
            if (!filesFound.containsKey(childName) && filesNeeded.contains(childName)) {
                filesFound.put(childName, child);
            }
        }
        findFilesAboveContext(filesFound, filesNeeded, dir.getParent());
    }

    /**
     * Crack open the tsconfig.json and look for a sourceRoot value. If it exists, return the folder referenced by that path. Otherwise, just return the parent
     * folder of tsconfig.json.
     *
     * @return
     */
    private void getTsSourceRoot() {
        FileObject tsConfigFile = requiredFileObjects.get(TSCONFIG_FILENAME);
        if (tsConfigFile == null) {
            return;
        }
        FileObject root = tsConfigFile.getParent();
        try {
            Object tsConfigObject = JSONValue.parse(new InputStreamReader(tsConfigFile.getInputStream()));
            if (tsConfigObject instanceof JSONObject) {
                tsConfig = (JSONObject) tsConfigObject;
                JSONObject compilerOptions = (JSONObject) tsConfig.get("compilerOptions");
                if (compilerOptions != null) {
                    String sourceRootValue = (String) compilerOptions.get("sourceRoot");
                    if (sourceRootValue != null) {
                        String[] sourceRootElements = sourceRootValue.split("/");
                        for (String sourceRootElement : sourceRootElements) {
                            if (null != sourceRootElement) {
                                switch (sourceRootElement) {
                                    case "..":
                                        root = root.getParent();
                                        break;
                                    case ".":
                                        // do nothing, just move on
                                        break;
                                    default:
                                        root = root.getFileObject(sourceRootElement);
                                        if (root == null || !root.isFolder()) {
                                            LOGGER.log(Level.SEVERE, "Can't resolve tsconfig.json compilerOptions.sourceRoot to a folder");
                                            return;
                                        }
                                        break;
                                }
                            }
                        }
                    }
                }
            } else {
                LOGGER.log(Level.SEVERE, "tsconfig.json file does not contain a JSON object: {0}", tsConfigFile.getPath());
            }
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "Unexpected error reading tsconfig.json file at " + tsConfigFile.getPath(), ex);
        }
        tsSourceRoot = root;
    }

    public void addExternalFiles() {
        if (!extRelPaths.isEmpty()) {
            String virtualPath = FileUtil.getRelativePath(context.getRoot(), tsSourceRoot.getParent());
            if (!virtualPath.isEmpty()) {
                virtualPath = virtualPath + '/';
            }
            for (Map.Entry<String, FileObject> fileEntry : extRelPaths.entrySet()) {
                String path = virtualPath + fileEntry.getValue().getNameExt();
                if (fileEntry.getValue().isFolder()) {
                    recursivelyAddFiles(path, fileEntry.getValue());
                } else if (!extFiles.containsKey(path)) {
                    Snapshot ss = TSCONFIG_FILENAME.equals(path) ? adjustTsConfigPaths(virtualPath) : Source.create(fileEntry.getValue()).createSnapshot();
//                    LOGGER.info(ss.getText().toString());
                    LOGGER.log(Level.FINER, "Adding virtual file: {0} => {1}", new Object[]{path, fileEntry.getValue().getPath()});
//                    LOGGER.info(ss.getText().toString());
                    TSService.addExternalFile(ss, path, context);
                    extFiles.put(path, fileEntry.getValue());
                }
            }
        }
    }

    private void recursivelyAddFiles(String virtualFolder, FileObject dir) {
        if (!dir.isFolder()) {
            return;
        }
        for (FileObject child : dir.getChildren()) {
            String path = virtualFolder + '/' + child.getNameExt();
            if (child.isFolder()) {
                recursivelyAddFiles(path, child);
            } else if ("text/typescript".equals(FileUtil.getMIMEType(child))) {
                if (!extFiles.containsKey(path)) {
                    LOGGER.log(Level.FINER, "Adding virtual file: {0} => {1}", new Object[]{path, child.getPath()});
                    TSService.addExternalFile(Source.create(child).createSnapshot(), path, context);
                    extFiles.put(path, child);
                }
            }
        }
    }

    /**
     * Since the tsconfig file is outside the source context, we're putting it into a virtual path which is inside the context. That will invalidate the path
     * properties in the tsconfig: "sourceRoot" and "outDir". We can rewrite those paths in the snapshot that we're sending into the NodeJS process.
     *
     * Since tsConfig is required to be in a folder that is a sibling to the source context parents, the adjustment relatively easy. We will remove parents from
     * the sourceDir property, and add corresponding "../" entries to the outDir.
     */
    private Snapshot adjustTsConfigPaths(String virtualPath) {
        Object compilerOptionsObj = tsConfig.get("compilerOptions");
        if (compilerOptionsObj != null && compilerOptionsObj instanceof JSONObject) {
            JSONObject compilerOptions = (JSONObject) compilerOptionsObj;
            if ((compilerOptions.containsKey("sourceRoot") || compilerOptions.containsKey("outDir"))
                    && !compilerOptions.containsKey("--sourceRootOriginal") && !compilerOptions.containsKey("--outDirOriginal")) {
                // copy the original paths to use as evidence of the change
                String sourceRoot = (String) compilerOptions.get("sourceRoot");
                compilerOptions.put("--sourceRootOriginal", sourceRoot);
                String outDir = (String) compilerOptions.get("outDir");
                compilerOptions.put("--outDirOriginal", outDir);

                if (sourceRoot.startsWith("./")) {
                    sourceRoot = sourceRoot.substring(2);
                }
                if (outDir.startsWith("./")) {
                    outDir = outDir.substring(2);
                }

                // compute the delta between the original path and the virtual path
                String parentActual = requiredFileObjects.get(TSCONFIG_FILENAME).getParent().getPath();
                String absoluteVirtual = contextRootPath + '/' + virtualPath;
                // the two paths must share a parent
                LOGGER.log(Level.FINER, "tsconfig.json valid virtual path: {0}", absoluteVirtual.startsWith(parentActual));
                String deltaPath = absoluteVirtual.substring(parentActual.length());
                if (deltaPath.startsWith("/")) {
                    deltaPath = deltaPath.substring(1);
                }
                LOGGER.log(Level.FINER, "tsconfig.json delta path: {0}", deltaPath);

                if (sourceRoot != null) {
                    // shorten the prefix of the sourceDir
                    compilerOptions.put("sourceRoot", "./"+sourceRoot.substring(deltaPath.length()));
                    LOGGER.log(Level.FINER, "Altered sourceRoot = ''{0}''", compilerOptions.get("sourceRoot"));
                }

                if (outDir != null) {
                    // extend the inheritance of the outDir
                    String[] segments = deltaPath.split("/");
                    for (int i = 0; i < segments.length; i++) {
                        outDir = "../" + outDir;
                    }
                    compilerOptions.put("outDir", outDir);
                    LOGGER.log(Level.FINER, "Altered outDir = ''{0}''", compilerOptions.get("outDir"));
                }
            }
        }
        try {
            FileSystem fs = tsSourceRoot.getFileSystem();
            FileObject tempTsConfigFo = fs.createTempFile(fs.getTempFolder(), "nbts-", ".json", true);
            FileLock lock = tempTsConfigFo.lock();
            try (OutputStream os = tempTsConfigFo.getOutputStream(lock)) {
                os.write(tsConfig.toJSONString().replace("\\/", "/").getBytes());
            }
            lock.releaseLock();
            Snapshot tsConfigSnapshot = Source.create(tempTsConfigFo).createSnapshot();
            return tsConfigSnapshot;
        } catch (IOException ex) {
            Exceptions.printStackTrace(ex);
            throw new RuntimeException("Exception createing temp tsconfig.json file", ex);
        }
    }
}
