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
package io.informant.local.ui;

import io.informant.config.CoarseProfilingConfig;
import io.informant.config.ConfigService;
import io.informant.config.ConfigService.OptimisticLockException;
import io.informant.config.FineProfilingConfig;
import io.informant.config.GeneralConfig;
import io.informant.config.PluginConfig;
import io.informant.config.PluginInfo;
import io.informant.config.PluginInfoCache;
import io.informant.config.PointcutConfig;
import io.informant.config.UserConfig;
import io.informant.local.store.RollingFile;
import io.informant.util.GsonFactory;
import io.informant.util.JsonElements;
import io.informant.util.Singleton;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Json service to read config data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class ConfigJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigJsonService.class);
    private static final Gson gson = GsonFactory.create();

    private final ConfigService configService;
    private final RollingFile rollingFile;
    private final PluginInfoCache pluginInfoCache;
    private final File dataDir;
    private final int uiPort;

    ConfigJsonService(ConfigService configService, RollingFile rollingFile,
            PluginInfoCache pluginInfoCache, File dataDir, int uiPort) {
        this.configService = configService;
        this.rollingFile = rollingFile;
        this.pluginInfoCache = pluginInfoCache;
        this.dataDir = dataDir;
        this.uiPort = uiPort;
    }

    @JsonServiceMethod
    String getConfig() throws IOException, SQLException {
        logger.debug("getConfig()");
        JsonObject configJson = new JsonObject();
        configJson.add("generalConfig", configService.getGeneralConfig().toJson());
        configJson.add("coarseProfilingConfig", configService.getCoarseProfilingConfig().toJson());
        configJson.add("fineProfilingConfig", configService.getFineProfilingConfig().toJson());
        configJson.add("userConfig", configService.getUserConfig().toJson());
        configJson.add("pluginInfos", gson.toJsonTree(pluginInfoCache.getPluginInfos()));
        configJson.add("pluginConfigs", getPluginConfigMapObject());
        configJson.add("pointcutConfigs", getPoincutConfigArray());
        configJson.addProperty("dataDir", dataDir.getCanonicalPath());
        configJson.addProperty("uiPort", uiPort);
        return configJson.toString();
    }

    @JsonServiceMethod
    String updateGeneralConfig(String configJson) throws IOException, OptimisticLockException {
        logger.debug("updateGeneralConfig(): configJson={}", configJson);
        JsonObject configObject = new JsonParser().parse(configJson).getAsJsonObject();
        GeneralConfig config = configService.getGeneralConfig();
        GeneralConfig.Builder builder = GeneralConfig.builder(config);
        builder.overlay(configObject);
        String priorVersionHash = JsonElements.getRequiredString(configObject, "versionHash");
        String updatedVersionHash = configService.updateGeneralConfig(builder.build(),
                priorVersionHash);
        // resize() doesn't do anything if the new and old value are the same
        rollingFile.resize(configService.getGeneralConfig().getRollingSizeMb() * 1024);
        return gson.toJson(updatedVersionHash);
    }

    @JsonServiceMethod
    String updateCoarseProfilingConfig(String configJson) throws OptimisticLockException {
        logger.debug("updateCoarseProfilingConfig(): configJson={}", configJson);
        JsonObject configObject = new JsonParser().parse(configJson).getAsJsonObject();
        CoarseProfilingConfig config = configService.getCoarseProfilingConfig();
        CoarseProfilingConfig.Builder builder = CoarseProfilingConfig.builder(config);
        builder.overlay(configObject);
        String priorVersionHash = JsonElements.getRequiredString(configObject, "versionHash");
        String updatedVersionHash = configService.updateCoarseProfilingConfig(builder.build(),
                priorVersionHash);
        return gson.toJson(updatedVersionHash);
    }

    @JsonServiceMethod
    String updateFineProfilingConfig(String configJson) throws OptimisticLockException {
        logger.debug("updateFineProfilingConfig(): configJson={}", configJson);
        JsonObject configObject = new JsonParser().parse(configJson).getAsJsonObject();
        FineProfilingConfig config = configService.getFineProfilingConfig();
        FineProfilingConfig.Builder builder = FineProfilingConfig.builder(config);
        builder.overlay(configObject);
        String priorVersionHash = JsonElements.getRequiredString(configObject, "versionHash");
        String updatedVersionHash = configService.updateFineProfilingConfig(builder.build(),
                priorVersionHash);
        return gson.toJson(updatedVersionHash);
    }

    @JsonServiceMethod
    String updateUserConfig(String configJson) throws OptimisticLockException {
        logger.debug("updateUserConfig(): configJson={}", configJson);
        JsonObject configObject = new JsonParser().parse(configJson).getAsJsonObject();
        UserConfig config = configService.getUserConfig();
        UserConfig.Builder builder = UserConfig.builder(config);
        builder.overlay(configObject);
        String priorVersionHash = JsonElements.getRequiredString(configObject, "versionHash");
        String updatedVersionHash = configService.updateUserConfig(builder.build(),
                priorVersionHash);
        return gson.toJson(updatedVersionHash);
    }

    @JsonServiceMethod
    String updatePluginConfig(String pluginId, String configJson) throws OptimisticLockException {
        logger.debug("updatePluginConfig(): pluginId={}, configJson={}", pluginId, configJson);
        JsonObject configObject = new JsonParser().parse(configJson).getAsJsonObject();
        PluginConfig config = configService.getPluginConfig(pluginId);
        if (config == null) {
            throw new IllegalArgumentException("Plugin id '" + pluginId + "' not found");
        }
        PluginConfig.Builder builder = PluginConfig.builder(config);
        builder.overlay(configObject);
        String priorVersionHash = JsonElements.getRequiredString(configObject, "versionHash");
        String updatedVersionHash = configService.updatePluginConfig(builder.build(),
                priorVersionHash);
        return gson.toJson(updatedVersionHash);
    }

    @JsonServiceMethod
    String addPointcutConfig(String configJson) throws JsonSyntaxException {
        logger.debug("addPointcutConfig(): configJson={}", configJson);
        PointcutConfig pointcut = gson.fromJson(configJson, PointcutConfig.Builder.class).build();
        String versionHash = configService.insertPointcutConfig(pointcut);
        return gson.toJson(versionHash);
    }

    @JsonServiceMethod
    String updatePointcutConfig(String priorVersionHash, String configJson)
            throws JsonSyntaxException {
        logger.debug("updatePointcutConfig(): priorVersionHash={}, configJson={}",
                priorVersionHash, configJson);
        PointcutConfig pointcutConfig = gson.fromJson(configJson, PointcutConfig.Builder.class)
                .build();
        String updatedVersionHash = configService.updatePointcutConfig(priorVersionHash,
                pointcutConfig);
        return gson.toJson(updatedVersionHash);
    }

    @JsonServiceMethod
    void removePointcutConfig(String versionHashJson) {
        logger.debug("removePointcutConfig(): versionHashJson={}", versionHashJson);
        configService.deletePointcutConfig(new JsonParser().parse(versionHashJson).getAsString());
    }

    private JsonObject getPluginConfigMapObject() {
        JsonObject mapObject = new JsonObject();
        for (PluginInfo pluginInfo : pluginInfoCache.getPluginInfos()) {
            PluginConfig pluginConfig = configService.getPluginConfig(pluginInfo.getId());
            if (pluginConfig == null) {
                throw new IllegalStateException("Plugin config not found for plugin id '"
                        + pluginInfo.getId() + "'");
            }
            mapObject.add(pluginInfo.getId(), pluginConfig.toJson());
        }
        return mapObject;
    }

    private JsonArray getPoincutConfigArray() {
        JsonArray jsonArray = new JsonArray();
        for (PointcutConfig pointcutConfig : configService.getPointcutConfigs()) {
            jsonArray.add(pointcutConfig.toJson());
        }
        return jsonArray;
    }
}
