/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.sling.cp2fm;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.apache.jackrabbit.vault.fs.io.AccessControlHandling;
import org.apache.jackrabbit.vault.fs.io.Archive;
import org.apache.jackrabbit.vault.fs.io.Archive.Entry;
import org.apache.jackrabbit.vault.packaging.Dependency;
import org.apache.jackrabbit.vault.packaging.PackageManager;
import org.apache.jackrabbit.vault.packaging.PackageProperties;
import org.apache.jackrabbit.vault.packaging.PackageType;
import org.apache.jackrabbit.vault.packaging.VaultPackage;
import org.apache.jackrabbit.vault.packaging.impl.PackageManagerImpl;
import org.apache.sling.cp2fm.handlers.DefaultEntryHandler;
import org.apache.sling.cp2fm.spi.BundlesDeployer;
import org.apache.sling.cp2fm.spi.EntryHandler;
import org.apache.sling.cp2fm.writers.InputStreamArtifactWriter;
import org.apache.sling.cp2fm.writers.MavenPomSupplierWriter;
import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Configuration;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.ExtensionType;
import org.apache.sling.feature.Extensions;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.io.json.FeatureJSONWriter;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.jar.JarArchiver;
import org.codehaus.plexus.archiver.util.DefaultFileSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContentPackage2FeatureModelConverter {

    private static final String CONTENT_PACKAGES = "content-packages";

    public static final String POM_TYPE = "pom";

    private static final String ZIP_TYPE = "zip";

    public static final String NAME_GROUP_ID = "groupId";

    public static final String NAME_ARTIFACT_ID = "artifactId";

    private static final String NAME_CLASSIFIER = "classifier";

    private static final String NAME_PATH = "path";

    private static final String FEATURE_CLASSIFIER = "cp2fm-converted-feature";

    private static final String SLING_OSGI_FEATURE_TILE_TYPE = "slingosgifeature";

    private static final String VAULT_PROPERTIES_FILE = "META-INF/vault/properties.xml";

    private static final String JSON_FILE_EXTENSION = ".json";

    private static final String[] INCLUDE_RESOURCES = { "definition/.content.xml", "config.xml", "settings.xml" };

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final PackageManager packageManager = new PackageManagerImpl();

    private final ServiceLoader<EntryHandler> entryHandlers = ServiceLoader.load(EntryHandler.class);

    private final EntryHandler defaultEntryHandler = new DefaultEntryHandler();

    private final Map<String, Feature> runModes = new HashMap<>();

    private final Set<String> dependencies = new HashSet<>();

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ss.SZ");

    private final RegexBasedResourceFilter filter = new RegexBasedResourceFilter();

    private BundlesDeployer artifactDeployer;

    private boolean strictValidation = false;

    private boolean mergeConfigurations = false;

    private int bundlesStartOrder = 0;

    private File outputDirectory;

    private Feature targetFeature = null;

    public ContentPackage2FeatureModelConverter setStrictValidation(boolean strictValidation) {
        this.strictValidation = strictValidation;
        return this;
    }

    public boolean isStrictValidation() {
        return strictValidation;
    }

    public boolean isMergeConfigurations() {
        return mergeConfigurations;
    }

    public ContentPackage2FeatureModelConverter setMergeConfigurations(boolean mergeConfigurations) {
        this.mergeConfigurations = mergeConfigurations;
        return this;
    }

    public ContentPackage2FeatureModelConverter setBundlesStartOrder(int bundlesStartOrder) {
        this.bundlesStartOrder = bundlesStartOrder;
        return this;
    }

    public ContentPackage2FeatureModelConverter setOutputDirectory(File outputDirectory) {
        this.outputDirectory = outputDirectory;
        return this;
    }

    public File getOutputDirectory() {
        return outputDirectory;
    }

    public Feature getTargetFeature() {
        return targetFeature;
    }

    public void addFilteringPattern(String filteringPattern) {
        Objects.requireNonNull(filteringPattern, "Null pattern to filter resources out is not a valid filtering pattern");
        if (filteringPattern.isEmpty()) {
            throw new IllegalArgumentException("Empty pattern to filter resources out is not a valid filtering pattern");
        }

        filter.addFilteringPattern(filteringPattern);
    }

    public Feature getRunMode(String runMode) {
        if (getTargetFeature() == null) {
            throw new IllegalStateException("Target Feature not initialized yet, please make sure convert() method was invoked first.");
        }

        if (runMode == null) {
            return getTargetFeature();
        }

        ArtifactId id = getTargetFeature().getId();

        return runModes.computeIfAbsent(runMode, k -> new Feature(new ArtifactId(id.getGroupId(),
                                                                                 id.getArtifactId(),
                                                                                 id.getVersion(),
                                                                                 id.getClassifier() + '-' + runMode,
                                                                                 id.getType())));
    }

    public BundlesDeployer getArtifactDeployer() {
        return artifactDeployer;
    }

    public void convert(File contentPackage) throws Exception {
        Objects.requireNonNull(contentPackage , "Null content-package can not be converted.");

        if (!contentPackage.exists() || !contentPackage.isFile()) {
            throw new IllegalArgumentException("Content-package "
                                            + contentPackage
                                            + " does not exist or it is not a valid file.");
        }

        if (outputDirectory == null) {
            throw new IllegalStateException("Null output directory not supported, it must be set before invoking the convert(File) method.");
        }

        Iterator<BundlesDeployer> artifactDeployerLoader = ServiceLoader.load(BundlesDeployer.class).iterator();
        if (!artifactDeployerLoader.hasNext()) {
            artifactDeployer = new DefaultBundlesDeployer(outputDirectory);
        } else {
            artifactDeployer = artifactDeployerLoader.next();
        }

        String userName = System.getProperty("user.name");

        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw new IllegalStateException("output directory "
                                            + outputDirectory
                                            + " does not exist and can not be created, please make sure current user '"
                                            + userName
                                            + " has enough rights to write on the File System.");
        }

        logger.info("Reading content-package '{}'...", contentPackage);

        try (VaultPackage vaultPackage = packageManager.open(contentPackage, strictValidation)) {
            logger.info("content-package '{}' successfully read!", contentPackage);

            PackageProperties packageProperties = vaultPackage.getProperties();

            String groupId = packageProperties.getProperty(NAME_GROUP_ID);
            String artifactId = packageProperties.getProperty(NAME_ARTIFACT_ID);
            String version = packageProperties.getProperty(PackageProperties.NAME_VERSION);

            targetFeature = new Feature(new ArtifactId(groupId,
                                                       artifactId,
                                                       version,
                                                       FEATURE_CLASSIFIER,
                                                       SLING_OSGI_FEATURE_TILE_TYPE));

            targetFeature.setDescription(packageProperties.getDescription());

            logger.info("Converting content-package '{}' to Feature File '{}'...", vaultPackage.getId(), targetFeature.getId());

            process(vaultPackage);

            // attach all unmatched resources as new content-package

            File deflatedDir = new File(outputDirectory, DefaultEntryHandler.TMP_DEFLATED);

            if (deflatedDir.listFiles().length > 0) {
                Properties properties = new Properties();
                copyProperty(PackageProperties.NAME_GROUP, packageProperties, properties);
                properties.setProperty(PackageProperties.NAME_NAME, packageProperties.getProperty(PackageProperties.NAME_NAME) + ' ' + FEATURE_CLASSIFIER);
                copyProperty(PackageProperties.NAME_VERSION, packageProperties, properties);
                copyProperty(NAME_GROUP_ID, packageProperties, properties);
                copyProperty(NAME_ARTIFACT_ID, packageProperties, properties);
                properties.setProperty(NAME_CLASSIFIER, FEATURE_CLASSIFIER);
                properties.setProperty(PackageProperties.NAME_DEPENDENCIES, dependencies.stream().collect(Collectors.joining(",")));
                properties.setProperty(PackageProperties.NAME_CREATED_BY, userName);
                properties.setProperty(PackageProperties.NAME_CREATED, dateFormat.format(new Date()));
                properties.setProperty(PackageProperties.NAME_REQUIRES_ROOT, String.valueOf(false));
                properties.setProperty(NAME_PATH, String.format("/etc/packages/%s/%s-%s.zip",
                                                                properties.getProperty(PackageProperties.NAME_GROUP),
                                                                properties.getProperty(NAME_ARTIFACT_ID),
                                                                FEATURE_CLASSIFIER));
                properties.setProperty(PackageProperties.NAME_PACKAGE_TYPE, PackageType.APPLICATION.name());
                properties.setProperty(PackageProperties.NAME_AC_HANDLING, AccessControlHandling.MERGE_PRESERVE.name());

                // generate the Vault properties XML file

                File xmlProperties = new File(deflatedDir, VAULT_PROPERTIES_FILE);
                xmlProperties.getParentFile().mkdirs();

                try (FileOutputStream fos = new FileOutputStream(xmlProperties)) {
                    properties.storeToXML(fos, null);
                }

                // copy the required resources

                for (String resource : INCLUDE_RESOURCES) {
                    File targetResource = new File(xmlProperties.getParentFile(), resource);
                    targetResource.getParentFile().mkdirs();

                    try (InputStream input = getClass().getResourceAsStream(resource);
                            OutputStream output = new FileOutputStream(targetResource)) {
                        IOUtils.copy(input, output);
                    }
                }

                // create the target archiver

                Archiver archiver = new JarArchiver();
                archiver.setIncludeEmptyDirs(true);

                File destFile = File.createTempFile(targetFeature.getId().getArtifactId(), '.' + ZIP_TYPE);

                archiver.setDestFile(destFile);
                archiver.addFileSet(new DefaultFileSet(deflatedDir));
                archiver.createArchive();

                try (InputStream input = new FileInputStream(destFile)) {
                    artifactDeployer.deploy(new InputStreamArtifactWriter(input),
                                            targetFeature.getId().getGroupId(),
                                            targetFeature.getId().getArtifactId(),
                                            targetFeature.getId().getVersion(),
                                            FEATURE_CLASSIFIER,
                                            ZIP_TYPE);

                    attach(null,
                           targetFeature.getId().getGroupId(),
                           targetFeature.getId().getArtifactId(),
                           targetFeature.getId().getVersion(),
                           FEATURE_CLASSIFIER,
                           ZIP_TYPE);
                } finally {
                    destFile.delete();
                }

                // deploy the new zip content-package to the local mvn bundles dir

                artifactDeployer.deploy(new MavenPomSupplierWriter(targetFeature.getId().getGroupId(),
                                                                          targetFeature.getId().getArtifactId(),
                                                                          targetFeature.getId().getVersion(),
                                                                          ZIP_TYPE),
                                               targetFeature.getId().getGroupId(),
                                               targetFeature.getId().getArtifactId(),
                                               targetFeature.getId().getVersion(),
                                               null,
                                               POM_TYPE);
            } else {
                logger.info("No resources to be repackaged.");
            }

            // finally serialize the Feature Model(s) file(s)

            seralize(targetFeature);

            if (!runModes.isEmpty()) {
                for (Feature runMode : runModes.values()) {
                    seralize(runMode);
                }
            }
        }
    }

    public void addConfiguration(String runMode, String pid, Dictionary<String, Object> configurationProperties) {
        if (!mergeConfigurations) {
            checkConfigurationExist(getTargetFeature(), pid);

            for (Feature runModeFeature : runModes.values()) {
                checkConfigurationExist(runModeFeature, pid);
            }
        }

        Feature feature = getRunMode(runMode);
        Configuration configuration = feature.getConfigurations().getConfiguration(pid);

        if (configuration == null) {
            configuration = new Configuration(pid);
            feature.getConfigurations().add(configuration);
        }

        Enumeration<String> keys = configurationProperties.keys();
        while (keys.hasMoreElements()) {
            String key = keys.nextElement();
            Object value = configurationProperties.get(key);
            configuration.getProperties().put(key, value);
        }
    }

    private static void checkConfigurationExist(Feature feature, String pid) {
        if (feature != null) {
            if (feature.getConfigurations().getConfiguration(pid) != null) {
                throw new IllegalStateException("Cinfiguration '"
                                                + pid
                                                + "' already defined in Feature Model '"
                                                + feature.getId().toMvnId()
                                                + "', can not be added");
            }
        }
    }

    private void seralize(Feature feature) throws Exception {
        StringBuilder fileName = new StringBuilder().append(feature.getId().getArtifactId());

        if (!FEATURE_CLASSIFIER.equals(feature.getId().getClassifier())) {
            fileName.append(feature.getId().getClassifier().substring(FEATURE_CLASSIFIER.length()));
        }

        fileName.append(JSON_FILE_EXTENSION);

        File targetFile = new File(outputDirectory, fileName.toString());

        logger.info("Conversion complete!", targetFile);
        logger.info("Writing resulting Feature File to '{}'...", targetFile);

        try (FileWriter targetWriter = new FileWriter(targetFile)) {
            FeatureJSONWriter.write(targetWriter, feature);

            logger.info("'{}' Feature File successfully written!", targetFile);
        }
    }

    public void processSubPackage(File contentPackage) throws Exception {
        Objects.requireNonNull(contentPackage, "Impossible to process a null vault package");

        try (VaultPackage vaultPackage = packageManager.open(contentPackage, strictValidation)) {
            process(vaultPackage);
        }
    }

    private void process(VaultPackage vaultPackage) throws Exception {
        Objects.requireNonNull(vaultPackage, "Impossible to process a null vault package");

        if (getTargetFeature() == null) {
            throw new IllegalStateException("Target Feature not initialized yet, please make sure convert() method was invoked first.");
        }

        dependencies.remove(vaultPackage.getId().toString());

        for (Dependency dependency : vaultPackage.getDependencies()) {
            dependencies.add(dependency.toString());
        }

        Archive archive = vaultPackage.getArchive();
        try {
            archive.open(strictValidation);

            Entry jcrRoot = archive.getJcrRoot();
            traverse(null, archive, jcrRoot);
        } finally {
            archive.close();
        }
    }

    private void traverse(String path, Archive archive, Entry entry) throws Exception {
        String entryPath = newPath(path, entry.getName());

        if (entry.isDirectory()) {
            for (Entry child : entry.getChildren()) {
                traverse(entryPath, archive, child);
            }

            return;
        }

        logger.info("Processing entry {}...", entryPath);

        if (filter.isFilteredOut(entryPath)) {
            throw new IllegalArgumentException("Path '"
                                               + entryPath
                                               + "' in archive "
                                               + archive.getMetaInf().getProperties()
                                               + " not allowed by user configuration, please check configured filtering patterns");
        }

        getEntryHandlerByEntryPath(entryPath).handle(entryPath, archive, entry, this);

        logger.info("Entry {} successfully processed.", entryPath);
    }

    private static String newPath(String path, String entryName) {
        if (path == null) {
            return entryName;
        }

        return path + '/' + entryName;
    }

    private EntryHandler getEntryHandlerByEntryPath(String path) {
        Iterator<EntryHandler> entryHandlersIterator = entryHandlers.iterator();
        while (entryHandlersIterator.hasNext()) {
            EntryHandler entryHandler = entryHandlersIterator.next();

            if (entryHandler.matches(path)) {
                return entryHandler;
            }
        }

        return defaultEntryHandler;
    }

    public void attach(String runMode,
                       String groupId,
                       String artifactId,
                       String version,
                       String classifier,
                       String type) {
        Objects.requireNonNull(groupId, "Artifact can not be attached to a feature without specifying a valid 'groupId'.");
        Objects.requireNonNull(artifactId, "Artifact can not be attached to a feature without specifying a valid 'artifactId'.");
        Objects.requireNonNull(version, "Artifact can not be attached to a feature without specifying a valid 'version'.");
        Objects.requireNonNull(type, "Artifact can not be attached to a feature without specifying a valid 'type'.");

        Artifact artifact = new Artifact(new ArtifactId(groupId, artifactId, version, classifier, type));

        Feature targetFeature = getRunMode(runMode);

        if (ZIP_TYPE.equals(type) ) {
            Extensions extensions = targetFeature.getExtensions();
            Extension extension = extensions.getByName(CONTENT_PACKAGES);

            if (extension == null) {
                extension = new Extension(ExtensionType.ARTIFACTS, CONTENT_PACKAGES, true);
                extensions.add(extension);
            }

            extension.getArtifacts().add(artifact);
        } else {
            artifact.setStartOrder(bundlesStartOrder);
            targetFeature.getBundles().add(artifact);
        }
    }

    private static void copyProperty(String key, PackageProperties source, Properties target) {
        target.setProperty(key, source.getProperty(key));
    }

}
