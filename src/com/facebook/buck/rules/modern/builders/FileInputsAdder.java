/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.rules.modern.builders;

import com.facebook.buck.remoteexecution.util.FileTreeBuilder;
import com.facebook.buck.remoteexecution.util.FileTreeBuilder.InputFile;
import com.facebook.buck.util.function.ThrowingFunction;
import com.google.common.base.Preconditions;
import com.google.common.hash.HashCode;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Used to add "complex" inputs to a FileTreeBuilder.
 *
 * <p>Unlike FileTreeBuilder, FileInputsAdder will accept calls to add entire directories or to add
 * paths where a parent directory is a symlink. It will convert these complicated cases into the
 * appropriate calls on the underlying FileTreeBuilder.
 */
class FileInputsAdder {
  private final Set<Path> addedInputs = new HashSet<>();
  private final Map<Path, Path> map = new HashMap<>();
  private final FileTreeBuilder inputsBuilder;
  private final Path cellPathPrefix;
  private final ThrowingFunction<Path, HashCode, IOException> fileHasher;
  private final ThrowingFunction<Path, Iterable<Path>, IOException> directoryLister;
  private final ThrowingFunction<Path, Path, IOException> symlinkReader;

  FileInputsAdder(
      FileTreeBuilder inputsBuilder,
      Path cellPathPrefix,
      ThrowingFunction<Path, HashCode, IOException> fileHasher,
      ThrowingFunction<Path, Iterable<Path>, IOException> directoryLister,
      ThrowingFunction<Path, Path, IOException> symlinkReader) {
    this.inputsBuilder = inputsBuilder;
    this.cellPathPrefix = cellPathPrefix;
    this.fileHasher = fileHasher;
    this.directoryLister = directoryLister;
    this.symlinkReader = symlinkReader;
  }

  /**
   * addInput() can accept a directory, and in that case it should add all the recursive children.
   * Also, addInput() may be called multiple times with the same path, or with a child or a parent
   * of a path that has already been added. To prevent repeatedly iterating over directory contents,
   * we maintain a cache of which paths have been added as inputs.
   */
  void addInput(Path path) throws IOException {
    if (addedInputs.contains(path)) {
      return;
    }
    Preconditions.checkState(path.isAbsolute(), "Expected absolute path: " + path);
    addedInputs.add(path);

    if (!path.startsWith(cellPathPrefix)) {
      // TODO(cjhopman): Should we map absolute paths to platform requirements?
      return;
    }

    Path target = addSingleInput(path);

    if (target.startsWith(cellPathPrefix)) {
      Iterable<Path> children = directoryLister.apply(target);
      if (children != null) {
        for (Path child : children) {
          addInput(child);
        }
      }
    }
  }

  /**
   * addSingleInput() may be called with the path to either a file or a directory (either of which
   * may be symlinks or have a symlink as one of its parents).
   *
   * <p>addSingleInput() returns the "canonical" path. i.e. it resolves all symlinks (except those
   * in parents of cellPathPrefix).
   *
   * <p>addSingleInput() ensures that all the symlinks in parents of this path are added to the
   * underlying FileTreeBuilder, and if path itself is a regular file, it too will be added to the
   * FileTreeBuilder.
   */
  private Path addSingleInput(Path path) throws IOException {
    Preconditions.checkState(path.normalize().equals(path));
    if (map.containsKey(path)) {
      return map.get(path);
    }

    if (!path.startsWith(cellPathPrefix)) {
      map.put(path, path);
      return path;
    }

    Path parent = path.getParent();
    if (parent.getNameCount() != cellPathPrefix.getNameCount()) {
      parent = addSingleInput(parent);
    }

    if (!parent.equals(path.getParent())) {
      // Some parent is a symlink, add the target.
      Path target = addSingleInput(parent.resolve(path.getFileName()));
      map.put(path, target);
      return target;
    }

    Path symlinkTarget = symlinkReader.apply(path);
    if (symlinkTarget != null) {
      Path resolvedTarget = path.getParent().resolve(symlinkTarget).normalize();

      boolean contained = resolvedTarget.startsWith(cellPathPrefix);
      Path fixedTarget = resolvedTarget;
      if (contained) {
        fixedTarget = parent.relativize(resolvedTarget);
      }
      inputsBuilder.addSymlink(cellPathPrefix.relativize(path), fixedTarget);

      Path target = contained ? addSingleInput(resolvedTarget) : resolvedTarget;
      map.put(path, target);
      return target;
    }

    if (Files.isRegularFile(path)) {
      inputsBuilder.addFile(
          cellPathPrefix.relativize(path),
          () ->
              new InputFile(
                  fileHasher.apply(path).toString(),
                  (int) Files.size(path),
                  Files.isExecutable(path),
                  () -> new FileInputStream(path.toFile())));
    }
    map.put(path, path);
    return path;
  }
}
