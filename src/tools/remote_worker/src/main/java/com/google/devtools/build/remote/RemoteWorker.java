// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.remote;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

import com.google.bytestream.ByteStreamGrpc.ByteStreamImplBase;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.remote.RemoteOptions;
import com.google.devtools.build.lib.remote.SimpleBlobStore;
import com.google.devtools.build.lib.remote.SimpleBlobStoreActionCache;
import com.google.devtools.build.lib.remote.SimpleBlobStoreFactory;
import com.google.devtools.build.lib.shell.Command;
import com.google.devtools.build.lib.shell.CommandException;
import com.google.devtools.build.lib.shell.CommandResult;
import com.google.devtools.build.lib.unix.UnixFileSystem;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.ProcessUtils;
import com.google.devtools.build.lib.util.SingleLineFormatter;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.JavaIoFileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.remoteexecution.v1test.ActionCacheGrpc.ActionCacheImplBase;
import com.google.devtools.remoteexecution.v1test.ContentAddressableStorageGrpc.ContentAddressableStorageImplBase;
import com.google.devtools.remoteexecution.v1test.ExecuteRequest;
import com.google.devtools.remoteexecution.v1test.ExecutionGrpc.ExecutionImplBase;
import com.google.watcher.v1.WatcherGrpc.WatcherImplBase;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Implements a remote worker that accepts work items as protobufs. The server implementation is
 * based on gRPC.
 */
public final class RemoteWorker {
  private static final Logger logger = Logger.getLogger(RemoteWorker.class.getName());

  private final RemoteWorkerOptions workerOptions;
  private final ActionCacheImplBase actionCacheServer;
  private final ByteStreamImplBase bsServer;
  private final ContentAddressableStorageImplBase casServer;
  private final WatcherImplBase watchServer;
  private final ExecutionImplBase execServer;

  static FileSystem getFileSystem() {
    return OS.getCurrent() == OS.WINDOWS ? new JavaIoFileSystem() : new UnixFileSystem();
  }

  public RemoteWorker(
      RemoteWorkerOptions workerOptions, SimpleBlobStoreActionCache cache, Path sandboxPath)
      throws IOException {
    this.workerOptions = workerOptions;
    this.actionCacheServer = new ActionCacheServer(cache);
    this.bsServer = new ByteStreamServer(cache);
    this.casServer = new CasServer(cache);

    if (workerOptions.workPath != null) {
      ConcurrentHashMap<String, ExecuteRequest> operationsCache = new ConcurrentHashMap<>();
      Path workPath = getFileSystem().getPath(workerOptions.workPath);
      FileSystemUtils.createDirectoryAndParents(workPath);
      watchServer = new WatcherServer(workPath, cache, workerOptions, operationsCache, sandboxPath);
      execServer = new ExecutionServer(operationsCache);
    } else {
      watchServer = null;
      execServer = null;
    }
  }

  public Server startServer() throws IOException {
    NettyServerBuilder b =
        NettyServerBuilder.forPort(workerOptions.listenPort)
            .addService(actionCacheServer)
            .addService(bsServer)
            .addService(casServer);

    if (execServer != null) {
      b.addService(execServer);
      b.addService(watchServer);
    } else {
      logger.info("Execution disabled, only serving cache requests.");
    }

    Server server = b.build();
    logger.log(INFO, "Starting gRPC server on port {0,number,#}.", workerOptions.listenPort);
    server.start();

    return server;
  }

  private void createPidFile() throws IOException {
    if (workerOptions.pidFile == null) {
      return;
    }

    final Path pidFile = getFileSystem().getPath(workerOptions.pidFile);
    try (PrintWriter printWriter = new PrintWriter(pidFile.getPathFile())) {
      printWriter.println(ProcessUtils.getpid());
    }

    Runtime.getRuntime()
        .addShutdownHook(
            new Thread() {
              @Override
              public void run() {
                try {
                  pidFile.delete();
                } catch (IOException e) {
                  System.err.println("Cannot remove pid file: " + pidFile);
                }
              }
            });
  }

  public static void main(String[] args) throws Exception {
    OptionsParser parser =
        OptionsParser.newOptionsParser(RemoteOptions.class, RemoteWorkerOptions.class);
    parser.parseAndExitUponError(args);
    RemoteOptions remoteOptions = parser.getOptions(RemoteOptions.class);
    RemoteWorkerOptions remoteWorkerOptions = parser.getOptions(RemoteWorkerOptions.class);

    Logger rootLog = Logger.getLogger("");
    rootLog.getHandlers()[0].setFormatter(new SingleLineFormatter());
    if (remoteWorkerOptions.debug) {
      Logger.getLogger("com.google").setLevel(FINE);
      rootLog.getHandlers()[0].setLevel(FINE);
    }

    Path sandboxPath = null;
    if (remoteWorkerOptions.sandboxing) {
      sandboxPath = prepareSandboxRunner(remoteWorkerOptions);
    }

    logger.info("Initializing in-memory cache server.");
    boolean usingRemoteCache = SimpleBlobStoreFactory.isRemoteCacheOptions(remoteOptions);
    if (!usingRemoteCache) {
      logger.warning("Not using remote cache. This should be used for testing only!");
    }

    SimpleBlobStore blobStore =
        usingRemoteCache
            ? SimpleBlobStoreFactory.create(remoteOptions)
            : new SimpleBlobStoreFactory.ConcurrentMapBlobStore(
                new ConcurrentHashMap<String, byte[]>());

    RemoteWorker worker =
        new RemoteWorker(
            remoteWorkerOptions, new SimpleBlobStoreActionCache(blobStore), sandboxPath);

    final Server server = worker.startServer();
    worker.createPidFile();
    server.awaitTermination();
  }

  private static Path prepareSandboxRunner(RemoteWorkerOptions remoteWorkerOptions) {
    if (OS.getCurrent() != OS.LINUX) {
      logger.severe("Sandboxing requested, but it is currently only available on Linux.");
      System.exit(1);
    }

    if (remoteWorkerOptions.workPath == null) {
      logger.severe("Sandboxing requested, but --work_path was not specified.");
      System.exit(1);
    }

    InputStream sandbox = RemoteWorker.class.getResourceAsStream("/main/tools/linux-sandbox");
    if (sandbox == null) {
      logger.severe(
          "Sandboxing requested, but could not find bundled linux-sandbox binary. "
              + "Please rebuild a remote_worker_deploy.jar on Linux to make this work.");
      System.exit(1);
    }

    Path sandboxPath = null;
    try {
      sandboxPath = getFileSystem().getPath(remoteWorkerOptions.workPath).getChild("linux-sandbox");
      try (FileOutputStream fos = new FileOutputStream(sandboxPath.getPathString())) {
        ByteStreams.copy(sandbox, fos);
      }
      sandboxPath.setExecutable(true);
    } catch (IOException e) {
      logger.log(SEVERE, "Could not extract the bundled linux-sandbox binary to " + sandboxPath, e);
      System.exit(1);
    }

    CommandResult cmdResult = null;
    Command cmd =
        new Command(
            ImmutableList.of(sandboxPath.getPathString(), "--", "true").toArray(new String[0]),
            ImmutableMap.<String, String>of(),
            sandboxPath.getParentDirectory().getPathFile());
    try {
      cmdResult = cmd.execute();
    } catch (CommandException e) {
      logger.log(
          SEVERE,
          "Sandboxing requested, but it failed to execute 'true' as a self-check: "
              + new String(cmdResult.getStderr(), UTF_8),
          e);
      System.exit(1);
    }

    return sandboxPath;
  }
}
