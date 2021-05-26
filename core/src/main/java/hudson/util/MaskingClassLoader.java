/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.util;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import edu.umd.cs.findbugs.annotations.NonNull;
import jenkins.YesNoMaybe;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link ClassLoader} that masks a specified set of classes
 * from its parent class loader.
 *
 * <p>
 * This code is used to create an isolated environment.
 *
 * @author Kohsuke Kawaguchi
 */
public class MaskingClassLoader extends ClassLoader {
    /**
     * Prefix of the packages that should be hidden.
     */
    private final List<String> masksClasses = new CopyOnWriteArrayList<>();

    private final List<String> masksResources = new CopyOnWriteArrayList<>();

    private final List<LibraryDef> masksLibraries = new CopyOnWriteArrayList<>();

    private final LoadingCache<String, LibraryDef> libraryCache = CacheBuilder.newBuilder().weakKeys().softValues().build(new CacheLoader<String, LibraryDef>() {
        @Override
        public LibraryDef load(final String key) throws Exception {

            URL url = new URL(key);
            if ("file".equalsIgnoreCase(url.getProtocol())) {
                JarFile f = new JarFile(url.getFile());
                final Optional<JarEntry> entry = f.stream().filter(jarEntry -> !jarEntry.isDirectory() && jarEntry.getName().startsWith("META-INF/maven") && jarEntry.getName().endsWith("pom.properties")).findFirst();
                if(entry.isPresent()) {
                    Properties p = new Properties();
                    try (InputStream in = f.getInputStream(entry.get())) {
                        p.load(in);
                        return LibraryDef.from(p);
                    } catch (Exception ignored) {
                        //TODO log
                    }
                }
            }
            return LibraryDef.VOID;
        }
    });

    public MaskingClassLoader(ClassLoader parent, String... masks) {
        this(parent, Arrays.asList(masks));
    }

    public MaskingClassLoader(ClassLoader parent, Collection<String> masks) {
        super(parent);
        this.masksClasses.addAll(masks);

        /*
         * The name of a resource is a '/'-separated path name
         */
        for (String mask : masks) {
            masksResources.add(mask.replace('.','/'));
        }
    }

    public MaskingClassLoader loadLibraryMasks(@NonNull String resource) {
        List<String> resourceLocations = new ArrayList<>();
        if (!resource.startsWith("/")) {
            resourceLocations.add("/" + getClass().getName().replace('.', '/') + "/" + resource);
        }
        resourceLocations.add(resource);
        for (String location : resourceLocations) {
            try (InputStream stream = getClass().getResourceAsStream(location)) {
                if (stream != null) {
                    final List<String> lines = IOUtils.readLines(stream, StandardCharsets.UTF_8);
                    for (String line : lines) {
                        line = line.trim();
                        if (line.startsWith("#")) {
                            continue;
                        }
                        LibraryDef lib = LibraryDef.from(line);
                        if (lib != LibraryDef.VOID) {
                            masksLibraries.add(lib);
                        }
                    }
                }
            } catch (IOException e) {
                Logger.getLogger(getClass().getName()).log(Level.WARNING, () -> String.format("Failed to load library masks from %s", location));
            }
        }
        return this;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        for (String mask : masksClasses) {
            if(name.startsWith(mask))
                throw new ClassNotFoundException();
        }

        final String resName = name.replace('.', '/') + ".class";
        final YesNoMaybe maskedLib = isResourceInMaskedLibrary(resName);
        if (maskedLib == YesNoMaybe.YES) {
            throw new ClassNotFoundException();
        } //TODO what if Maybe?

        return super.loadClass(name, resolve);
    }

    private YesNoMaybe isResourceInMaskedLibrary(final String name) {
        URL res = super.getResource(name);
        if (res != null) {
            return isResourceInMaskedLibrary(res);
        } else {
            return YesNoMaybe.NO;
        }
    }

    private YesNoMaybe isResourceInMaskedLibrary(@NonNull final URL res) {
        if ("jar".equalsIgnoreCase(res.getProtocol()) && res.getPath().contains("!")) {
            //file:/home/rsandell/.m2/repository/com/google/guava/guava/11.0.1/guava-11.0.1.jar!/com/google/common/collect/ArrayListMultimap.class
            final String jarPath = res.getPath().substring(0, res.getPath().indexOf('!'));
            try {
                LibraryDef lib = libraryCache.get(jarPath);
                if (isMaskedLibrary(lib)) {
                    return YesNoMaybe.YES;
                } else {
                    return YesNoMaybe.NO;
                }
            } catch (ExecutionException e) {
                e.printStackTrace();
                return YesNoMaybe.MAYBE;
            }
        } else {
            return YesNoMaybe.MAYBE;
        }
    }

    private boolean isMaskedLibrary(final LibraryDef lib) {
        if (lib == null || lib == LibraryDef.VOID) {
            return false;
        }
        for (LibraryDef masksLibrary : masksLibraries) {
            if (masksLibrary.equals(lib)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public URL getResource(String name) {
        if (isMasked(name)) return null;
        if (isResourceInMaskedLibrary(name) == YesNoMaybe.YES) {
            return null;
        }
        return super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        if (isMasked(name)) return Collections.emptyEnumeration();

        final Enumeration<URL> resources = super.getResources(name);
        ArrayList<URL> filtered = new ArrayList<>();
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            if (isResourceInMaskedLibrary(url) != YesNoMaybe.YES) {
                filtered.add(url);
            }
        }
        return Collections.enumeration(filtered);
    }

    public void add(String prefix) {
        masksClasses.add(prefix);
        if(prefix !=null){
            masksResources.add(prefix.replace('.','/'));
        }
    }

    private boolean isMasked(String name) {
        for (String mask : masksResources) {
            if(name.startsWith(mask))
                return true;
        }
        return false;
    }

    private static class LibraryDef {

        static final LibraryDef VOID = new LibraryDef("", "");

        final String groupId;
        final String artifactId;

        LibraryDef(final String groupId, final @NonNull String artifactId) {
            this.groupId = groupId;
            this.artifactId = artifactId;
        }

        @Override
        public boolean equals(final Object obj) {
            if (obj instanceof LibraryDef) {
                return equals((LibraryDef) obj);
            }
            return false;
        }

        public boolean equals(LibraryDef lib) {
            if (StringUtils.isNotEmpty(groupId) && StringUtils.isNotEmpty(lib.groupId)) {
                if (!groupId.equals(lib.groupId)) {
                    return false;
                }
            }
            return artifactId.equals(lib.artifactId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(groupId, artifactId);
        }

        static LibraryDef from(Properties p) {
            return new LibraryDef(p.getProperty("groupId"), p.getProperty("artifactId"));
        }

        public static LibraryDef from(final String line) {
            if (line.contains(":")) {
                final String[] split = line.split(":");
                return new LibraryDef(split[0], split[1]);
            } else {
                return new LibraryDef(null, line);
            }
        }
    }
}
