/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.persistence;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.nifi.cluster.protocol.DataFlow;
import org.apache.nifi.controller.FlowController;
import org.apache.nifi.controller.StandardFlowSynchronizer;
import org.apache.nifi.controller.UninheritableFlowException;
import org.apache.nifi.controller.serialization.FlowSerializationException;
import org.apache.nifi.controller.serialization.FlowSynchronizationException;
import org.apache.nifi.controller.serialization.FlowSynchronizer;
import org.apache.nifi.controller.serialization.StandardFlowSerializer;
import org.apache.nifi.encrypt.StringEncryptor;
import org.apache.nifi.util.NiFiProperties;
import org.apache.nifi.util.file.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StandardXMLFlowConfigurationDAO implements FlowConfigurationDAO {

    public static final String CONFIGURATION_ARCHIVE_DIR_KEY = "nifi.flow.configuration.archive.dir";

    private final Path flowXmlPath;
    private final StringEncryptor encryptor;

    private static final Logger LOG = LoggerFactory.getLogger(StandardXMLFlowConfigurationDAO.class);

    public StandardXMLFlowConfigurationDAO(final Path flowXml, final StringEncryptor encryptor) throws IOException {
        final File flowXmlFile = flowXml.toFile();
        if (!flowXmlFile.exists()) {
            // createDirectories would throw an exception if the directory exists but is a symbolic link
            if (Files.notExists(flowXml.getParent())) {
                Files.createDirectories(flowXml.getParent());
            }
            Files.createFile(flowXml);
            //TODO: find a better solution. With Windows 7 and Java 7, Files.isWritable(source.getParent()) returns false, even when it should be true.
        } else if (!flowXmlFile.canRead() || !flowXmlFile.canWrite()) {
            throw new IOException(flowXml + " exists but you have insufficient read/write privileges");
        }

        this.flowXmlPath = flowXml;
        this.encryptor = encryptor;
    }

    @Override
    public synchronized void load(final FlowController controller, final DataFlow dataFlow)
            throws IOException, FlowSerializationException, FlowSynchronizationException, UninheritableFlowException {

        final FlowSynchronizer flowSynchronizer = new StandardFlowSynchronizer(encryptor);
        controller.synchronize(flowSynchronizer, dataFlow);
        save(new ByteArrayInputStream(dataFlow.getFlow()));
    }

    @Override
    public synchronized void load(final OutputStream os) throws IOException {
        final File file = flowXmlPath.toFile();
        if (!file.exists() || file.length() == 0) {
            return;
        }

        try (final InputStream inStream = Files.newInputStream(flowXmlPath, StandardOpenOption.READ);
                final InputStream gzipIn = new GZIPInputStream(inStream)) {
            FileUtils.copy(gzipIn, os);
        }
    }

    @Override
    public void load(final OutputStream os, final boolean compressed) throws IOException {
        if (compressed) {
            Files.copy(flowXmlPath, os);
        } else {
            load(os);
        }
    }

    @Override
    public synchronized void save(final InputStream is) throws IOException {
        try (final OutputStream outStream = Files.newOutputStream(flowXmlPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
                final OutputStream gzipOut = new GZIPOutputStream(outStream)) {
            FileUtils.copy(is, gzipOut);
        }
    }

    @Override
    public void save(final FlowController flow) throws IOException {
        LOG.trace("Saving flow to disk");
        try (final OutputStream outStream = Files.newOutputStream(flowXmlPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
                final OutputStream gzipOut = new GZIPOutputStream(outStream)) {
            save(flow, gzipOut);
        }
        LOG.debug("Finished saving flow to disk");
    }

    @Override
    public synchronized void save(final FlowController flow, final OutputStream os) throws IOException {
        try {
            final StandardFlowSerializer xmlTransformer = new StandardFlowSerializer(encryptor);
            flow.serialize(xmlTransformer, os);
        } catch (final FlowSerializationException fse) {
            throw new IOException(fse);
        }
    }

    @Override
    public synchronized void save(final FlowController controller, final boolean archive) throws IOException {
        if (null == controller) {
            throw new NullPointerException();
        }

        Path tempFile;
        Path configFile;

        configFile = flowXmlPath;
        tempFile = configFile.getParent().resolve(configFile.toFile().getName() + ".new.xml.gz");

        try (final OutputStream fileOut = Files.newOutputStream(tempFile);
                final OutputStream outStream = new GZIPOutputStream(fileOut)) {

            final StandardFlowSerializer xmlTransformer = new StandardFlowSerializer(encryptor);
            controller.serialize(xmlTransformer, outStream);

            Files.deleteIfExists(configFile);
            FileUtils.renameFile(tempFile.toFile(), configFile.toFile(), 5, true);
        } catch (final FlowSerializationException fse) {
            throw new IOException(fse);
        } finally {
            Files.deleteIfExists(tempFile);
        }

        if (archive) {
            try {
                final File archiveFile = createArchiveFile();
                Files.copy(configFile, archiveFile.toPath());
            } catch (final Exception ex) {
                LOG.warn("Unable to archive flow configuration as requested due to " + ex);
                if (LOG.isDebugEnabled()) {
                    LOG.warn("", ex);
                }
            }
        }
    }

    @Override
    public File createArchiveFile() throws IOException {
        final String archiveDirVal = NiFiProperties.getInstance().getProperty(CONFIGURATION_ARCHIVE_DIR_KEY);
        final Path archiveDir = (archiveDirVal == null || archiveDirVal.equals("")) ? flowXmlPath.getParent().resolve("archive") : new File(archiveDirVal).toPath();
        Files.createDirectories(archiveDir);

        if (!Files.isDirectory(archiveDir)) {
            throw new IOException("Archive directory doesn't appear to be a directory " + archiveDir);
        }
        final Path archiveFile = archiveDir.resolve(System.nanoTime() + "-" + flowXmlPath.toFile().getName());
        return archiveFile.toFile();
    }
}
