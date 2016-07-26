/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.api.internal.tasks.cache.config;

import org.gradle.StartParameter;
import org.gradle.api.internal.tasks.cache.LocalDirectoryTaskOutputCache;
import org.gradle.api.internal.tasks.cache.TaskCacheKey;
import org.gradle.api.internal.tasks.cache.TaskOutputCache;
import org.gradle.api.internal.tasks.cache.TaskOutputCacheFactory;
import org.gradle.api.internal.tasks.cache.TaskOutputReader;
import org.gradle.api.internal.tasks.cache.TaskOutputWriter;

import java.io.File;
import java.io.IOException;

public class DefaultTaskCaching implements TaskCachingInternal {
    private static final TaskOutputCacheFactory NO_OP_TASK_CACHE_FACTORY = new TaskOutputCacheFactory() {
        @Override
        public TaskOutputCache createCache(StartParameter startParameter) {
            return new TaskOutputCache() {
                @Override
                public TaskOutputReader get(TaskCacheKey key) throws IOException {
                    return null;
                }

                @Override
                public void put(TaskCacheKey key, TaskOutputWriter output) throws IOException {
                }

                @Override
                public String getDescription() {
                    return "no task output cache";
                }
            };
        }
    };
    private TaskOutputCacheFactory factory = NO_OP_TASK_CACHE_FACTORY;

    @Override
    public void useLocalCache() {
        this.factory = new TaskOutputCacheFactory() {
            @Override
            public TaskOutputCache createCache(StartParameter startParameter) {
                String cacheDirectoryPath = startParameter.getSystemPropertiesArgs().get("org.gradle.cache.tasks.directory");
                File cacheDirectory = cacheDirectoryPath != null
                    ? new File(cacheDirectoryPath)
                    : new File(startParameter.getGradleUserHomeDir(), "task-cache");
                return new LocalDirectoryTaskOutputCache(cacheDirectory);
            }
        };
    }

    @Override
    public void useLocalCache(final File directory) {
        this.factory = new TaskOutputCacheFactory() {
            @Override
            public TaskOutputCache createCache(StartParameter startParameter) {
                return new LocalDirectoryTaskOutputCache(directory);
            }
        };
    }

    @Override
    public void useCacheFactory(TaskOutputCacheFactory factory) {
        this.factory = factory;
    }

    @Override
    public TaskOutputCacheFactory getCacheFactory() {
        return factory;
    }
}
