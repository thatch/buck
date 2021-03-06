/*
 * Copyright 2015-present Facebook, Inc.
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
package com.facebook.buck.features.project.intellij;

import com.facebook.buck.android.AndroidBinaryDescriptionArg;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidLibraryGraphEnhancer;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.AndroidResourceDescriptionArg;
import com.facebook.buck.android.DummyRDotJava;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.features.project.intellij.model.IjModuleFactoryResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.AnnotationProcessingParams;
import com.facebook.buck.jvm.java.CompilerOutputPaths;
import com.facebook.buck.jvm.java.JavaLibraryRules;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import com.facebook.buck.util.Optionals;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

class DefaultIjModuleFactoryResolver implements IjModuleFactoryResolver {

  private final ActionGraphBuilder graphBuilder;
  private final SourcePathResolver sourcePathResolver;
  private final SourcePathRuleFinder ruleFinder;
  private final ProjectFilesystem projectFilesystem;
  private final Set<BuildTarget> requiredBuildTargets;

  DefaultIjModuleFactoryResolver(
      ActionGraphBuilder graphBuilder,
      SourcePathResolver sourcePathResolver,
      SourcePathRuleFinder ruleFinder,
      ProjectFilesystem projectFilesystem,
      Set<BuildTarget> requiredBuildTargets) {
    this.graphBuilder = graphBuilder;
    this.sourcePathResolver = sourcePathResolver;
    this.ruleFinder = ruleFinder;
    this.projectFilesystem = projectFilesystem;
    this.requiredBuildTargets = requiredBuildTargets;
  }

  @Override
  public Optional<Path> getDummyRDotJavaPath(TargetNode<?> targetNode) {
    BuildTarget dummyRDotJavaTarget =
        AndroidLibraryGraphEnhancer.getDummyRDotJavaTarget(targetNode.getBuildTarget());
    Optional<BuildRule> dummyRDotJavaRule = graphBuilder.getRuleOptional(dummyRDotJavaTarget);
    if (dummyRDotJavaRule.isPresent()) {
      requiredBuildTargets.add(dummyRDotJavaTarget);
      return Optional.of(
          DummyRDotJava.getOutputJarPath(dummyRDotJavaTarget, projectFilesystem));
    }
    return Optional.empty();
  }

  @Override
  public Path getAndroidManifestPath(TargetNode<AndroidBinaryDescriptionArg> targetNode) {
    AndroidBinaryDescriptionArg arg = targetNode.getConstructorArg();
    Optional<SourcePath> manifestSourcePath = arg.getManifest();
    if (!manifestSourcePath.isPresent()) {
      manifestSourcePath = arg.getManifestSkeleton();
    }
    if (!manifestSourcePath.isPresent()) {
      throw new IllegalArgumentException(
          "android_binary "
              + targetNode.getBuildTarget()
              + " did not specify manifest or manifest_skeleton");
    }
    return sourcePathResolver.getAbsolutePath(manifestSourcePath.get());
  }

  @Override
  public Optional<Path> getLibraryAndroidManifestPath(
      TargetNode<AndroidLibraryDescription.CoreArg> targetNode) {
    Optional<SourcePath> manifestPath = targetNode.getConstructorArg().getManifest();
    return manifestPath.map(sourcePathResolver::getAbsolutePath).map(projectFilesystem::relativize);
  }

  @Override
  public Optional<Path> getProguardConfigPath(TargetNode<AndroidBinaryDescriptionArg> targetNode) {
    return targetNode
        .getConstructorArg()
        .getProguardConfig()
        .map(this::getRelativePathAndRecordRule);
  }

  @Override
  public Optional<Path> getAndroidResourcePath(
      TargetNode<AndroidResourceDescriptionArg> targetNode) {
    return AndroidResourceDescription.getResDirectoryForProject(graphBuilder, targetNode)
        .map(this::getRelativePathAndRecordRule);
  }

  @Override
  public Optional<Path> getAssetsPath(TargetNode<AndroidResourceDescriptionArg> targetNode) {
    return AndroidResourceDescription.getAssetsDirectoryForProject(graphBuilder, targetNode)
        .map(this::getRelativePathAndRecordRule);
  }

  @Override
  public Optional<Path> getAnnotationOutputPath(TargetNode<? extends JvmLibraryArg> targetNode) {
    AnnotationProcessingParams annotationProcessingParams =
        targetNode
            .getConstructorArg()
            .buildAnnotationProcessingParams(targetNode.getBuildTarget(), graphBuilder);
    if (annotationProcessingParams == null || annotationProcessingParams.isEmpty()) {
      return Optional.empty();
    }

    return CompilerOutputPaths.getAnnotationPath(projectFilesystem, targetNode.getBuildTarget());
  }

  @Override
  public Optional<Path> getAbiAnnotationOutputPath(TargetNode<? extends JvmLibraryArg> targetNode) {
    Optional<BuildRule> buildRule = graphBuilder.getRuleOptional(targetNode.getBuildTarget());
    if (buildRule.isPresent()) {
      ImmutableSortedSet<BuildRule> sourceOnlyAbiRules =
          JavaLibraryRules.getSourceOnlyAbiRules(graphBuilder, ImmutableSet.of(buildRule.get()));
      if (!sourceOnlyAbiRules.isEmpty()) {
        return getAnnotationOutputPath(
            targetNode.copyWithFlavors(sourceOnlyAbiRules.first().getBuildTarget().getFlavors()));
      }
    }
    return Optional.empty();
  }

  @Override
  public Optional<Path> getCompilerOutputPath(TargetNode<? extends JvmLibraryArg> targetNode) {
    BuildTarget buildTarget = targetNode.getBuildTarget();
    Path compilerOutputPath = CompilerOutputPaths.getOutputJarPath(buildTarget, projectFilesystem);
    return Optional.of(compilerOutputPath);
  }

  private Path getRelativePathAndRecordRule(SourcePath sourcePath) {
    requiredBuildTargets.addAll(
        Optionals.toStream(ruleFinder.getRule(sourcePath).map(BuildRule::getBuildTarget))
            .collect(Collectors.toList()));
    return sourcePathResolver.getRelativePath(sourcePath);
  }
}
