/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.netbeans.modules.jackpot30.remotingapi;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileSystem;
import org.openide.filesystems.FileUtil;
import org.openide.filesystems.URLMapper;
import org.openide.modules.Places;
import org.openide.util.Exceptions;
import org.openide.util.RequestProcessor;

/**
 *
 * @author Tomas Zezula
 */
public final class CacheFolder {

    private static final Logger LOG = Logger.getLogger(CacheFolder.class.getName());
    private static final RequestProcessor RP = new RequestProcessor(CacheFolder.class.getName(), 1, false, false);
    private static final RequestProcessor.Task SAVER = RP.create(new Saver());
    private static final int SLIDING_WINDOW = 500;

    private static final String SEGMENTS_FILE = "segments";      //NOI18N
    private static final String SLICE_PREFIX = "s";              //NOI18N

    //@GuardedBy("CacheFolder.class")
    private static FileObject cacheFolder;
    //@GuardedBy("CacheFolder.class")
    private static Properties segments;
    //@GuardedBy("CacheFolder.class")
    private static Map<String, String> invertedSegments;
    //@GuardedBy("CacheFolder.class")
    private static int index = 0;


    //@NotThreadSafe
    @org.netbeans.api.annotations.common.SuppressWarnings(
        value="LI_LAZY_INIT_UPDATE_STATIC"
        /*,justification="Caller already holds a monitor"*/)
    private static void loadSegments(FileObject folder) throws IOException {
        assert Thread.holdsLock(CacheFolder.class);
        if (segments == null) {
            assert folder != null;
            segments = new Properties ();
            invertedSegments = new HashMap<String,String> ();
            final FileObject segmentsFile =  folder.getFileObject(SEGMENTS_FILE);
            if (segmentsFile!=null) {
                final InputStream in = segmentsFile.getInputStream();
                try {
                    segments.load (in);
                } finally {
                    in.close();
                }
            }
            for (Map.Entry entry : segments.entrySet()) {
                String segment = (String) entry.getKey();
                String root = (String) entry.getValue();
                invertedSegments.put(root,segment);
                try {
                    index = Math.max (index,Integer.parseInt(segment.substring(SLICE_PREFIX.length())));
                } catch (NumberFormatException nfe) {
                    LOG.log(Level.FINE, null, nfe);
                }
            }
        }
    }


    private static void storeSegments(FileObject folder) throws IOException {
        assert Thread.holdsLock(CacheFolder.class);
        assert folder != null;
        //It's safer to use FileUtil.createData(File) than FileUtil.createData(FileObject, String)
        //see issue #173094
        final File _file = FileUtil.toFile(folder);
        assert _file != null;
        final FileObject segmentsFile = FileUtil.createData(new File(_file, SEGMENTS_FILE));
        final OutputStream out = segmentsFile.getOutputStream();
        try {
            segments.store(out,null);
        } finally {
            out.close();
        }
    }

    public static synchronized URL getSourceRootForDataFolder (final FileObject dataFolder) {
        final FileObject segFolder = dataFolder.getParent();
        if (segFolder == null || !segFolder.equals(cacheFolder)) {
            return null;
        }
        String source = segments.getProperty(dataFolder.getName());
        if (source != null) {
            try {
                return new URL (source);
            } catch (IOException ioe) {
                LOG.log(Level.FINE, null, ioe);
            }
        }
        return null;
    }

    public static FileObject getDataFolder (final URL root) throws IOException {
        return getDataFolder(root, false);
    }

    public static FileObject getDataFolder (final URL root, final boolean onlyIfAlreadyExists) throws IOException {
        final String rootName = root.toExternalForm();
        final FileObject _cacheFolder = getCacheFolder();
        String slice;
        synchronized (CacheFolder.class) {
            loadSegments(_cacheFolder);
            slice = invertedSegments.get (rootName);
            if (slice == null) {
                if (onlyIfAlreadyExists) {
                    return null;
                }
                slice = SLICE_PREFIX + (++index);
                while (segments.getProperty(slice) != null) {
                    slice = SLICE_PREFIX + (++index);
                }
                segments.put (slice,rootName);
                invertedSegments.put(rootName, slice);
                SAVER.schedule(SLIDING_WINDOW);
            }
        }
        assert slice != null;
        if (onlyIfAlreadyExists) {
            return cacheFolder.getFileObject(slice);
        } else {
            return FileUtil.createFolder(_cacheFolder, slice);
        }
    }

    public static synchronized Iterable<? extends FileObject> findRootsWithCacheUnderFolder(FileObject folder) throws IOException {
        URL folderURL = folder.toURL();
        String prefix = folderURL.toExternalForm();
        final FileObject _cacheFolder = getCacheFolder();
        List<FileObject> result = new LinkedList<FileObject>();
        loadSegments(_cacheFolder);
        for (Entry<String, String> e : invertedSegments.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                FileObject fo = URLMapper.findFileObject(new URL(e.getKey()));

                if (fo != null) {
                    result.add(fo);
                }
            }
        }

        return result;
    }

    public static synchronized FileObject getCacheFolder () {
        if (cacheFolder == null) {
            File cache = Places.getCacheSubdirectory("index"); // NOI18N
            if (!cache.isDirectory()) {
                throw new IllegalStateException("Indices cache folder " + cache.getAbsolutePath() + " is not a folder"); //NOI18N
            }
            if (!cache.canRead()) {
                throw new IllegalStateException("Can't read from indices cache folder " + cache.getAbsolutePath()); //NOI18N
            }
            if (!cache.canWrite()) {
                throw new IllegalStateException("Can't write to indices cache folder " + cache.getAbsolutePath()); //NOI18N
            }

            cacheFolder = FileUtil.toFileObject(cache);
            if (cacheFolder == null) {
                throw new IllegalStateException("Can't convert indices cache folder " + cache.getAbsolutePath() + " to FileObject"); //NOI18N
            }
        }
        return cacheFolder;
    }


    /**
     * Only for unit tests! It's used also by CslTestBase, which is not in the
     * same package, hence the public keyword.
     *
     */
    public static void setCacheFolder (final FileObject folder) {
        SAVER.schedule(0);
        SAVER.waitFinished();
        synchronized (CacheFolder.class) {
            assert folder != null && folder.canRead() && folder.canWrite();
            cacheFolder = folder;
            segments = null;
            invertedSegments = null;
            index = 0;
        }
    }

    private CacheFolder() {
        // no-op
    }

    private static class Saver implements Runnable {
        @Override
        public void run() {
            try {
                final FileObject cf = getCacheFolder();
                // #170182 - preventing filesystem events being fired from under the CacheFolder.class lock
                cf.getFileSystem().runAtomicAction(new FileSystem.AtomicAction() {
                    @Override
                    public void run() throws IOException {
                        synchronized (CacheFolder.class) {
                            if (segments == null) return ;
                            storeSegments(cf);
                        }
                    }
                });
            } catch (IOException ioe) {
                Exceptions.printStackTrace(ioe);
            }
        }
    }
}
