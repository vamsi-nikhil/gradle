/*
 * Copyright 2014 the original author or authors.
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
package org.gradle.api.internal.tasks.compile.daemon;

import com.google.common.collect.Lists;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.compile.BaseForkOptions;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.language.base.internal.compile.CompileSpec;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.language.base.internal.compile.RequiresServices;
import org.gradle.workers.internal.DaemonForkOptions;
import org.gradle.workers.internal.DefaultWorkResult;
import org.gradle.workers.internal.SimpleActionExecutionSpec;
import org.gradle.workers.internal.Worker;
import org.gradle.workers.internal.WorkerFactory;

import javax.inject.Inject;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.gradle.process.internal.util.MergeOptionsUtil.mergeHeapSize;
import static org.gradle.process.internal.util.MergeOptionsUtil.normalized;

public abstract class AbstractDaemonCompiler<T extends CompileSpec> implements Compiler<T> {
    private final Compiler<T> delegate;
    private final WorkerFactory workerFactory;

    public AbstractDaemonCompiler(Compiler<T> delegate, WorkerFactory workerFactory) {
        this.delegate = delegate;
        this.workerFactory = workerFactory;
    }

    public Compiler<T> getDelegate() {
        return delegate;
    }

    @Override
    public WorkResult execute(T spec) {
        DaemonForkOptions daemonForkOptions = toDaemonForkOptions(spec);
        Worker worker = workerFactory.getWorker(daemonForkOptions);
        DefaultWorkResult result = worker.execute(new SimpleActionExecutionSpec(CompilerCallable.class, "compiler daemon", new Object[] {delegate, spec}));
        if (result.isSuccess()) {
            return result;
        } else {
            throw UncheckedException.throwAsUncheckedException(result.getException());
        }
    }

    protected abstract DaemonForkOptions toDaemonForkOptions(T spec);

    protected BaseForkOptions mergeForkOptions(BaseForkOptions left, BaseForkOptions right) {
        BaseForkOptions merged = new BaseForkOptions();
        merged.setMemoryInitialSize(mergeHeapSize(left.getMemoryInitialSize(), right.getMemoryInitialSize()));
        merged.setMemoryMaximumSize(mergeHeapSize(left.getMemoryMaximumSize(), right.getMemoryMaximumSize()));
        Set<String> mergedJvmArgs = normalized(left.getJvmArgs());
        mergedJvmArgs.addAll(normalized(right.getJvmArgs()));
        merged.setJvmArgs(Lists.newArrayList(mergedJvmArgs));
        return merged;
    }

    private static class CompilerCallable<T extends CompileSpec> implements Callable<WorkResult> {
        private final Compiler<T> compiler;
        private final T compileSpec;
        private final ServiceRegistry serviceRegistry;

        @Inject
        public CompilerCallable(Compiler<T> compiler, T compileSpec, ServiceRegistry serviceRegistry) {
            this.compiler = compiler;
            this.compileSpec = compileSpec;
            this.serviceRegistry = serviceRegistry;
        }

        @Override
        public WorkResult call() throws Exception {
            // TODO Pass the compiler as a class and instantiate it rather than this hack
            if (compiler instanceof RequiresServices) {
                ((RequiresServices) compiler).setServiceRegistry(serviceRegistry);
            }
            return compiler.execute(compileSpec);
        }
    }
}
