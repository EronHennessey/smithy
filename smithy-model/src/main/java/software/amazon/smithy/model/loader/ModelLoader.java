/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.model.loader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.function.Supplier;
import java.util.logging.Logger;
import software.amazon.smithy.model.SourceException;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.node.StringNode;

/**
 * Used to load Smithy models from .json, .smithy, and .jar files.
 */
final class ModelLoader {

    private static final Logger LOGGER = Logger.getLogger(ModelLoader.class.getName());
    private static final String SMITHY = "smithy";

    private ModelLoader() {}

    /**
     * Loads the contents of a model into a {@code LoaderVisitor}.
     *
     * <p>The format contained in the supplied {@code InputStream} is
     * determined based on the file extension in the provided
     * {@code filename}.
     *
     * @param filename Filename Filename to assign to the model.
     * @param contentSupplier The supplier that provides an InputStream. The
     *   supplied {@code InputStream} is automatically closed when the loader
     *   has finished reading from it.
     * @param visitor The visitor to update while loading.
     * @return Returns true if the file could be loaded, and false if not.
     * @throws SourceException if there is an error reading from the contents.
     */
    static boolean load(String filename, Supplier<InputStream> contentSupplier, LoaderVisitor visitor) {
        if (filename.endsWith(".json")) {
            return loadParsedNode(Node.parse(contentSupplier.get(), filename), visitor);
        } else if (filename.endsWith(".smithy")) {
            IdlModelLoader.load(filename, contentSupplier, visitor);
            return true;
        } else if (filename.endsWith(".jar")) {
            return loadJar(filename, visitor);
        } else if (filename.equals(SourceLocation.NONE.getFilename())) {
            // Assume it's JSON if there's a N/A filename.
            return loadParsedNode(Node.parse(contentSupplier.get(), filename), visitor);
        } else {
            return false;
        }
    }

    // Loads all supported JSON formats. Each JSON format is expected to have
    // a top-level version property that contains a string. This version
    // is then used to delegate loading to different versions of the
    // Smithy JSON AST format.
    //
    // This loader supports version 1.0. Support for 0.5 and 0.4 was removed in 0.10.
    static boolean loadParsedNode(Node node, LoaderVisitor visitor) {
        ObjectNode model = node.expectObjectNode("Smithy documents must be an object. Found {type}.");
        StringNode version = model.expectStringMember(SMITHY);

        if (visitor.isVersionSupported(version.getValue())) {
            AstModelLoader.INSTANCE.load(model, visitor);
            return true;
        } else {
            throw new ModelSyntaxException("Unsupported Smithy version number: " + version.getValue(), version);
        }
    }

    // Allows importing JAR files by discovering models inside of a JAR file.
    // This is similar to model discovery, but done using an explicit import.
    private static boolean loadJar(String filename, LoaderVisitor visitor) {
        URL manifestUrl = ModelDiscovery.createSmithyJarManifestUrl(filename);
        LOGGER.fine(() -> "Loading Smithy model imports from JAR: " + manifestUrl);

        for (URL model : ModelDiscovery.findModels(manifestUrl)) {
            try {
                URLConnection connection = model.openConnection();

                if (visitor.hasProperty(ModelAssembler.DISABLE_JAR_CACHE)) {
                    connection.setUseCaches(false);
                }

                load(model.toExternalForm(), () -> {
                    try {
                        return connection.getInputStream();
                    } catch (IOException e) {
                        throw throwIoJarException(model, e);
                    }
                }, visitor);

            } catch (IOException e) {
                throw throwIoJarException(model, e);
            }
        }

        return true;
    }

    private static ModelImportException throwIoJarException(URL model, Throwable e) {
        return new ModelImportException(
                String.format("Error loading Smithy model from URL `%s`: %s", model, e.getMessage()), e);
    }
}
