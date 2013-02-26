/**
 * Copyright 2011-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.informant;

import io.informant.api.PluginServices;
import io.informant.config.ConfigModule;
import io.informant.config.DataDir;
import io.informant.core.TraceModule;
import io.informant.local.store.DataSource;
import io.informant.local.store.DataSourceModule;
import io.informant.local.store.TraceSinkModule;
import io.informant.local.ui.LocalUiModule;
import io.informant.util.OnlyUsedByTests;
import io.informant.util.Static;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;

/**
 * This class is registered as the Premain-Class in the MANIFEST.MF of informant-core.jar:
 * 
 * Premain-Class: io.informant.MainEntryPoint
 * 
 * This defines the entry point when the JVM is launched via -javaagent:informant-core.jar.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class MainEntryPoint {

    private static final Logger logger = LoggerFactory.getLogger(MainEntryPoint.class);

    @Nullable
    private static volatile ConfigModule configModule;
    @Nullable
    private static volatile DataSourceModule dataSourceModule;
    @Nullable
    private static volatile TraceSinkModule traceSinkModule;
    @Nullable
    private static volatile TraceModule traceModule;
    @Nullable
    private static volatile LocalUiModule uiModule;

    private MainEntryPoint() {}

    // javaagent entry point
    public static void premain(@Nullable String agentArgs, Instrumentation instrumentation) {
        logger.debug("premain()");
        ImmutableMap<String, String> properties = getInformantProperties();
        // ...WithNoWarning since warning is displayed during start so no need for it twice
        File dataDir = DataDir.getDataDirWithNoWarning(properties);
        if (DataSource.tryUnlockDatabase(new File(dataDir, "informant.lock.db"))) {
            try {
                start(properties, instrumentation);
            } catch (Throwable t) {
                logger.error("informant failed to start: {}", t.getMessage(), t);
            }
        } else {
            // this is common when stopping tomcat since 'catalina.sh stop' launches a java process
            // to stop the tomcat jvm, and it uses the same JAVA_OPTS environment variable that may
            // have been used to specify '-javaagent:informant.jar', in which case Informant tries
            // to start up, but it finds the h2 database is locked (by the tomcat jvm).
            // this can be avoided by using CATALINA_OPTS instead of JAVA_OPTS to specify
            // -javaagent:informant.jar, since CATALINA_OPTS is not used by the 'catalina.sh stop'.
            // however, when running tomcat from inside eclipse, the tomcat server adapter uses the
            // same 'VM arguments' for both starting and stopping tomcat, so this code path seems
            // inevitable at least in this case
            //
            // no need for logging in the special (but common) case described above
            if (!isTomcatStop()) {
                logger.error("embedded database '" + dataDir.getAbsolutePath()
                        + "' is locked by another process.");
            }
        }
    }

    // called via reflection from io.informant.api.PluginServices
    public static PluginServices getPluginServices(String pluginId) {
        return traceModule.getPluginServices(pluginId);
    }

    // used by Viewer
    static void start() throws Exception {
        start(getInformantProperties(), null);
    }

    private static void start(@ReadOnly Map<String, String> properties,
            @Nullable Instrumentation instrumentation) throws Exception {
        logger.debug("start(): classLoader={}", MainEntryPoint.class.getClassLoader());
        configModule = new ConfigModule(properties);
        dataSourceModule = new DataSourceModule(configModule, properties);
        traceSinkModule = new TraceSinkModule(configModule, dataSourceModule);
        traceModule = new TraceModule(configModule, traceSinkModule.getTraceSink());
        uiModule = new LocalUiModule(configModule, dataSourceModule, traceSinkModule, traceModule,
                properties);
        if (instrumentation != null) {
            instrumentation.addTransformer(traceModule.createWeavingClassFileTransformer());
        }
    }

    private static ImmutableMap<String, String> getInformantProperties() {
        ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
        for (Entry<Object, Object> entry : System.getProperties().entrySet()) {
            if (entry.getKey() instanceof String && entry.getValue() instanceof String
                    && ((String) entry.getKey()).startsWith("informant.")) {
                String key = (String) entry.getKey();
                builder.put(key.substring("informant.".length()), (String) entry.getValue());
            }
        }
        return builder.build();
    }

    private static boolean isTomcatStop() {
        return Objects.equal(System.getProperty("sun.java.command"),
                "org.apache.catalina.startup.Bootstrap stop");
    }

    @OnlyUsedByTests
    public static void start(@ReadOnly Map<String, String> properties) throws Exception {
        start(properties, null);
    }

    @OnlyUsedByTests
    @Nullable
    public static ConfigModule getConfigModule() {
        return configModule;
    }

    @OnlyUsedByTests
    @Nullable
    public static DataSourceModule getDataSourceModule() {
        return dataSourceModule;
    }

    @OnlyUsedByTests
    @Nullable
    public static TraceSinkModule getTraceSinkModule() {
        return traceSinkModule;
    }

    @OnlyUsedByTests
    @Nullable
    public static TraceModule getCoreModule() {
        return traceModule;
    }

    @OnlyUsedByTests
    @Nullable
    public static LocalUiModule getUiModule() {
        return uiModule;
    }

    @OnlyUsedByTests
    public static void shutdown() {
        logger.debug("shutdown()");
        uiModule.close();
        traceModule.close();
        traceSinkModule.close();
        dataSourceModule.close();
        configModule = null;
        traceSinkModule = null;
        traceModule = null;
        uiModule = null;
    }
}
