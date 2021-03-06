/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.classpath;

import org.gradle.api.UncheckedIOException;
import org.gradle.api.file.RelativePath;
import org.gradle.api.internal.file.archive.ZipEntry;
import org.gradle.api.internal.file.archive.ZipInput;
import org.gradle.api.internal.file.archive.impl.FileZipInput;
import org.gradle.internal.Pair;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.file.FileException;
import org.gradle.internal.file.FileType;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.hash.Hasher;
import org.gradle.internal.hash.Hashing;
import org.gradle.internal.io.ExponentialBackoff;
import org.gradle.internal.snapshot.FileSystemLocationSnapshot;
import org.gradle.util.GFileUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.util.concurrent.TimeUnit;

import static java.lang.String.format;

class InstrumentingClasspathFileTransformer implements ClasspathFileTransformer {
    private static final Logger LOGGER = LoggerFactory.getLogger(InstrumentingClasspathFileTransformer.class);
    private static final int CACHE_FORMAT = 2;

    private final ClasspathWalker classpathWalker;
    private final ClasspathBuilder classpathBuilder;
    private final CachedClasspathTransformer.Transform transform;
    private final HashCode configHash;

    public InstrumentingClasspathFileTransformer(ClasspathWalker classpathWalker, ClasspathBuilder classpathBuilder, CachedClasspathTransformer.Transform transform) {
        this.classpathWalker = classpathWalker;
        this.classpathBuilder = classpathBuilder;
        this.transform = transform;
        this.configHash = configHashFor(transform);
    }

    private HashCode configHashFor(CachedClasspathTransformer.Transform transform) {
        Hasher hasher = Hashing.defaultFunction().newHasher();
        hasher.putInt(CACHE_FORMAT);
        transform.applyConfigurationTo(hasher);
        return hasher.hash();
    }

    @Override
    public File transform(File source, FileSystemLocationSnapshot sourceSnapshot, File cacheDir) {
        String name = sourceSnapshot.getType() == FileType.Directory
            ? source.getName() + ".jar"
            : source.getName();
        String destFileName = hashOf(sourceSnapshot).toString() + '/' + name;
        return transformIfNeeded(source, cacheDir, destFileName);
    }

    private File transformIfNeeded(File source, File cacheDir, String destFileName) {
        File receipt = new File(cacheDir, destFileName + ".receipt");
        File transformed = new File(cacheDir, destFileName);
        if (receipt.isFile()) {
            return transformed;
        }
        try {
            transform(source, transformed);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof FileAlreadyExistsException) {
                // A concurrent writer has already started writing to the file.
                // We run identical transforms concurrently and we can sometimes finish two transforms at the same
                // time in a way that Files.move (see [ClasspathBuilder.nonReplacingJar]) will see [transformed] created before
                // the move is done.
                // Just wait until the transformed file is ready for consumption.
                LOGGER.debug("Instrumented classpath file '{}' already exists.", destFileName, e);
                waitForReceiptOf(destFileName, receipt);
                return transformed;
            } else {
                throw e;
            }
        }
        try {
            receipt.createNewFile();
        } catch (IOException e) {
            LOGGER.debug("Failed to create receipt for instrumented classpath file '{}'.", destFileName, e);
        }
        return transformed;
    }

    private void waitForReceiptOf(String destFileName, File receipt) {
        if (!waitFor(receipt)) {
            throw new IllegalStateException(
                format("Timeout waiting for instrumented classpath file: '%s'.", destFileName)
            );
        }
    }

    /**
     * Waits up to 60 seconds for the given file to appear.
     *
     * @return true when the file appears before the timeout, false otherwise.
     */
    private boolean waitFor(File file) {
        try {
            return ExponentialBackoff
                .of(60, TimeUnit.SECONDS)
                .retryUntil(() -> file.isFile() ? file : null) != null;
        } catch (InterruptedException | IOException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    private HashCode hashOf(FileSystemLocationSnapshot sourceSnapshot) {
        Hasher hasher = Hashing.defaultFunction().newHasher();
        hasher.putHash(configHash);
        // TODO - apply runtime classpath normalization?
        hasher.putHash(sourceSnapshot.getHash());
        return hasher.hash();
    }

    private void transform(File source, File dest) {
        if (isSignedJar(source)) {
            LOGGER.debug("Signed archive '{}'. Skipping instrumentation.", source.getName());
            GFileUtils.copyFile(source, dest);
        } else {
            instrument(source, dest);
        }
    }

    private void instrument(File source, File dest) {
        classpathBuilder.nonReplacingJar(dest, builder -> {
            try {
                visitEntries(source, builder);
            } catch (FileException e) {
                // Badly formed archive, so discard the contents and produce an empty JAR
                LOGGER.debug("Malformed archive '{}'. Discarding contents.", source.getName(), e);
            }
        });
    }

    private void visitEntries(File source, ClasspathBuilder.EntryBuilder builder) throws IOException, FileException {
        classpathWalker.visit(source, entry -> {
            if (entry.getName().endsWith(".class")) {
                ClassReader reader = new ClassReader(entry.getContent());
                ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                Pair<RelativePath, ClassVisitor> chain = transform.apply(entry, classWriter);
                reader.accept(chain.right, 0);
                byte[] bytes = classWriter.toByteArray();
                builder.put(chain.left.getPathString(), bytes);
            } else {
                builder.put(entry.getName(), entry.getContent());
            }
        });
    }

    private boolean isSignedJar(File source) {
        if (!source.isFile()) {
            return false;
        }
        try (ZipInput entries = FileZipInput.create(source)) {
            for (ZipEntry entry : entries) {
                String entryName = entry.getName();
                if (entryName.startsWith("META-INF/") && entryName.endsWith(".SF")) {
                    return true;
                }
            }
        } catch (FileException e) {
            // Ignore malformed archive
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return false;
    }
}
