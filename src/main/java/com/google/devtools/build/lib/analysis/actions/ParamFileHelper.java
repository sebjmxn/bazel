// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis.actions;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.ParameterFile;
import com.google.devtools.build.lib.analysis.AnalysisEnvironment;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.List;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * A command-line implementation that wraps another command line and puts the arguments in a
 * parameter file if necessary
 *
 * <p>The Linux kernel has a limit for the command line length, and that can be easily reached
 * if, for example, a command is listing all its inputs on the command line.
 */
@Immutable
public final class ParamFileHelper {

  /**
   * Returns a params file artifact or null for a given command description.
   *
   * <p>Returns null if parameter files are not to be used according to paramFileInfo, or if the
   * command line is short enough that a parameter file is not needed.
   *
   * <p>Make sure to add the returned artifact (if not null) as an input of the corresponding
   * action.
   *
   * @param executableArgs leading arguments that should never be wrapped in a parameter file
   * @param commandLine a {@link CommandLine} that provides the arguments (in addition to
   *     executableArgs)
   * @param paramFileInfo parameter file information
   * @param configuration the configuration
   * @param analysisEnvironment the analysis environment
   * @param outputs outputs of the action (used to construct a filename for the params file)
   */
  static Artifact getParamsFileMaybe(
      List<String> executableArgs,
      CommandLine commandLine,
      @Nullable ParamFileInfo paramFileInfo,
      BuildConfiguration configuration,
      AnalysisEnvironment analysisEnvironment,
      Iterable<Artifact> outputs) {
    if (paramFileInfo == null) {
      return null;
    }
    if (!paramFileInfo.always()
        && getParamFileSize(executableArgs, commandLine) < configuration.getMinParamFileSize()) {
      return null;
    }

    Artifact output = Iterables.getFirst(outputs, null);
    Preconditions.checkNotNull(output);
    PathFragment paramFilePath = ParameterFile.derivePath(output.getRootRelativePath());
    return analysisEnvironment.getDerivedArtifact(paramFilePath, output.getRoot());
  }

  /**
   * Creates a command line using an external params file.
   *
   * <p>Call this with the result of {@link #getParamsFileMaybe} if it is not null.
   *
   * @param executableArgs leading arguments that should never be wrapped in a parameter file
   * @param paramFileInfo parameter file information
   * @param parameterFile the output parameter file artifact
   */
  public static CommandLine createWithParamsFile(
      ImmutableList<String> executableArgs, ParamFileInfo paramFileInfo, Artifact parameterFile) {
    return CustomCommandLine.builder()
        .addAll(executableArgs)
        // This is actually a constant, but there is no way to suppress the warning
        .addWithDynamicPrefix(paramFileInfo.getFlag(), parameterFile)
        .build();
  }

  /**
   * Creates an action to write the parameter file.
   *
   * @param commandLine a {@link CommandLine} that provides the arguments (in addition to
   *     executableArgs)
   * @param owner owner of the action
   * @param parameterFile the output parameter file artifact
   * @param paramFileInfo parameter file information
   */
  public static ParameterFileWriteAction createParameterFileWriteAction(
      CommandLine commandLine,
      ActionOwner owner,
      Artifact parameterFile,
      ParamFileInfo paramFileInfo) {
    return new ParameterFileWriteAction(
        owner, parameterFile, commandLine, paramFileInfo.getFileType(), paramFileInfo.getCharset());
  }

  /**
   * Creates a command line without using a params file.
   *
   * <p>Call this if {@link #getParamsFileMaybe} returns null.
   *
   * @param executableArgs leading arguments that should never be wrapped in a parameter file
   * @param commandLine a {@link CommandLine} that provides the arguments (in addition to
   *     executableArgs)
   */
  public static CommandLine createWithoutParamsFile(
      List<String> executableArgs, CommandLine commandLine) {
    if (executableArgs.isEmpty()) {
      return commandLine;
    }
    return CommandLine.concat(ImmutableList.copyOf(executableArgs), commandLine);
  }

  /** Estimates the params file size for the given arguments. */
  private static int getParamFileSize(List<String> executableArgs, CommandLine commandLine) {
    try {
      Iterable<String> actualArguments = commandLine.arguments();
      return getParamFileSize(executableArgs) + getParamFileSize(actualArguments);
    } catch (CommandLineExpansionException e) {
      // CommandLineExpansionException is thrown deterministically. We can ignore
      // it here and pretend that a params file is not necessary at this stage,
      // and an error will be thrown later at execution time.
      return 0;
    }
  }

  private static int getParamFileSize(Iterable<String> args) {
    int size = 0;
    for (String s : args) {
      size += s.length() + 1;
    }
    return size;
  }
}
