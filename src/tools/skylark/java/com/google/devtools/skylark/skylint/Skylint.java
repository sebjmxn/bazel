// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.skylark.skylint;

import com.google.devtools.build.lib.syntax.BuildFileAST;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** The main class for the skylint binary. */
public class Skylint {
  public static void main(String[] args) throws IOException {
    String content =
        new String(
            Files.readAllBytes(Paths.get(args[0]).toAbsolutePath()), StandardCharsets.ISO_8859_1);
    BuildFileAST ast =
        BuildFileAST.parseSkylarkString(
            event -> {
              System.err.println(event);
            },
            content);
    List<Issue> issues = NamingConventionsChecker.check(ast);
    for (Issue issue : issues) {
      System.out.println(issue);
    }
  }
}
