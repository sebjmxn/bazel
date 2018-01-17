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

package com.google.devtools.build.lib.skyframe.serialization.autocodec;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.skyframe.serialization.InjectingObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationCodeGenerator.Context;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationCodeGenerator.Marshaller;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationCodeGenerator.PrimitiveValueSerializationCodeGenerator;
import com.google.devtools.build.lib.skyframe.serialization.strings.StringCodecs;
import com.google.protobuf.AbstractMessage;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.ProtocolMessageEnum;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/** Class containing all {@link Marshaller} instances. */
class Marshallers {
  private final ProcessingEnvironment env;

  Marshallers(ProcessingEnvironment env) {
    this.env = env;
  }

  void writeSerializationCode(Context context) {
    if (context.canBeNull()) {
      context.builder.beginControlFlow("if ($L != null)", context.name);
      context.builder.addStatement("codedOut.writeBoolNoTag(true)");
    }
    getMatchingCodeGenerator(context.type).addSerializationCode(context);
    if (context.canBeNull()) {
      context.builder.nextControlFlow("else");
      context.builder.addStatement("codedOut.writeBoolNoTag(false)");
      context.builder.endControlFlow();
    }
  }

  void writeDeserializationCode(Context context) {
    if (context.canBeNull()) {
      context.builder.addStatement("$T $L = null", context.getTypeName(), context.name);
      context.builder.beginControlFlow("if (codedIn.readBool())");
    } else {
      context.builder.addStatement("$T $L", context.getTypeName(), context.name);
    }
    getMatchingCodeGenerator(context.type).addDeserializationCode(context);
    if (requiresNullityCheck(context)) {
      context.builder.endControlFlow();
    }
  }

  private static boolean requiresNullityCheck(Context context) {
    return !(context.type instanceof PrimitiveType);
  }

  private SerializationCodeGenerator getMatchingCodeGenerator(TypeMirror type) {
    if (type.getKind() == TypeKind.ARRAY) {
      return arrayCodeGenerator;
    }

    if (type instanceof PrimitiveType) {
      PrimitiveType primitiveType = (PrimitiveType) type;
      return primitiveGenerators
          .stream()
          .filter(generator -> generator.matches((PrimitiveType) type))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("No generator for: " + primitiveType));
    }

    // TODO(cpeyser): Refactor primitive handling from AutoCodecProcessor.java
    if (!(type instanceof DeclaredType)) {
      throw new IllegalArgumentException(
          "Can only serialize primitive, array or declared fields, found " + type);
    }
    DeclaredType declaredType = (DeclaredType) type;

    return marshallers
        .stream()
        .filter(marshaller -> marshaller.matches(declaredType))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "No marshaller for: "
                        + ((TypeElement) declaredType.asElement()).getQualifiedName()));
  }

  private final SerializationCodeGenerator arrayCodeGenerator =
      new SerializationCodeGenerator() {
        @Override
        public void addSerializationCode(Context context) {
          String length = context.makeName("length");
          context.builder.addStatement("int $L = $L.length", length, context.name);
          context.builder.addStatement("codedOut.writeInt32NoTag($L)", length);
          Context repeated =
              context.with(
                  ((ArrayType) context.type).getComponentType(), context.makeName("repeated"));
          String indexName = context.makeName("i");
          context.builder.beginControlFlow(
              "for(int $L = 0; $L < $L; ++$L)", indexName, indexName, length, indexName);
          context.builder.addStatement(
              "$T $L = $L[$L]", repeated.getTypeName(), repeated.name, context.name, indexName);
          writeSerializationCode(repeated);
          context.builder.endControlFlow();
        }

        @Override
        public void addDeserializationCode(Context context) {
          Context repeated =
              context.with(
                  ((ArrayType) context.type).getComponentType(), context.makeName("repeated"));
          String lengthName = context.makeName("length");
          context.builder.addStatement("int $L = codedIn.readInt32()", lengthName);

          String resultName = context.makeName("result");
          context.builder.addStatement(
              "$T[] $L = new $T[$L]",
              repeated.getTypeName(),
              resultName,
              repeated.getTypeName(),
              lengthName);
          String indexName = context.makeName("i");
          context.builder.beginControlFlow(
              "for (int $L = 0; $L < $L; ++$L)", indexName, indexName, lengthName, indexName);
          writeDeserializationCode(repeated);
          context.builder.addStatement("$L[$L] = $L", resultName, indexName, repeated.name);
          context.builder.endControlFlow();
          context.builder.addStatement("$L = $L", context.name, resultName);
        }
      };

  private final PrimitiveValueSerializationCodeGenerator intCodeGenerator =
      new PrimitiveValueSerializationCodeGenerator() {
        @Override
        public boolean matches(PrimitiveType type) {
          return type.getKind() == TypeKind.INT;
        }

        @Override
        public void addSerializationCode(Context context) {
          context.builder.addStatement("codedOut.writeInt32NoTag($L)", context.name);
        }

        @Override
        public void addDeserializationCode(Context context) {
          context.builder.addStatement("$L = codedIn.readInt32()", context.name);
        }
      };

  private final Marshaller enumMarshaller =
      new Marshaller() {
        @Override
        public boolean matches(DeclaredType type) {
          return env.getTypeUtils().asElement(type).getKind() == ElementKind.ENUM;
        }

        @Override
        public void addSerializationCode(Context context) {
          if (isProtoEnum(context.getDeclaredType())) {
            context.builder.addStatement("codedOut.writeInt32NoTag($L.getNumber())", context.name);
          } else {
            context.builder.addStatement("codedOut.writeInt32NoTag($L.ordinal())", context.name);
          }
        }

        @Override
        public void addDeserializationCode(Context context) {
          if (isProtoEnum(context.getDeclaredType())) {
            context.builder.addStatement(
                "$L = $T.forNumber(codedIn.readInt32())", context.name, context.getTypeName());
          } else {
            // TODO(shahan): memoize this expensive call to values().
            context.builder.addStatement(
                "$L = $T.values()[codedIn.readInt32()]", context.name, context.getTypeName());
          }
        }

        private boolean isProtoEnum(DeclaredType type) {
          return env.getTypeUtils()
              .isSubtype(
                  type,
                  env.getElementUtils()
                      .getTypeElement(ProtocolMessageEnum.class.getCanonicalName())
                      .asType());
        }
      };

  private final Marshaller stringMarshaller =
      new Marshaller() {
        @Override
        public boolean matches(DeclaredType type) {
          return matchesType(type, String.class);
        }

        @Override
        public void addSerializationCode(Context context) {
          context.builder.addStatement(
              "$T.asciiOptimized().serialize($L, codedOut)", StringCodecs.class, context.name);
        }

        @Override
        public void addDeserializationCode(Context context) {
          context.builder.addStatement(
              "$L = $T.asciiOptimized().deserialize(codedIn)", context.name, StringCodecs.class);
        }
      };

  private final Marshaller optionalMarshaller =
      new Marshaller() {
        @Override
        public boolean matches(DeclaredType type) {
          return matchesErased(type, Optional.class);
        }

        @Override
        public void addSerializationCode(Context context) {
          DeclaredType optionalType =
              (DeclaredType) context.getDeclaredType().getTypeArguments().get(0);
          writeSerializationCode(context.with(optionalType, context.name + ".orNull()"));
        }

        @Override
        public void addDeserializationCode(Context context) {
          DeclaredType optionalType =
              (DeclaredType) context.getDeclaredType().getTypeArguments().get(0);
          String optionalName = context.makeName("optional");
          writeDeserializationCode(context.with(optionalType, optionalName));
          context.builder.addStatement(
              "$L = $T.fromNullable($L)", context.name, Optional.class, optionalName);
        }
      };

  private final Marshaller mapEntryMarshaller =
      new Marshaller() {
        @Override
        public boolean matches(DeclaredType type) {
          return matchesErased(type, Map.Entry.class);
        }

        @Override
        public void addSerializationCode(Context context) {
          DeclaredType keyType = (DeclaredType) context.getDeclaredType().getTypeArguments().get(0);
          writeSerializationCode(context.with(keyType, context.name + ".getKey()"));
          DeclaredType valueType =
              (DeclaredType) context.getDeclaredType().getTypeArguments().get(1);
          writeSerializationCode(context.with(valueType, context.name + ".getValue()"));
        }

        @Override
        public void addDeserializationCode(Context context) {
          DeclaredType keyType = (DeclaredType) context.getDeclaredType().getTypeArguments().get(0);
          String keyName = context.makeName("key");
          writeDeserializationCode(context.with(keyType, keyName));
          DeclaredType valueType =
              (DeclaredType) context.getDeclaredType().getTypeArguments().get(1);
          String valueName = context.makeName("value");
          writeDeserializationCode(context.with(valueType, valueName));
          context.builder.addStatement(
              "$L = $T.immutableEntry($L, $L)", context.name, Maps.class, keyName, valueName);
        }
      };

  private final Marshaller listMarshaller =
      new Marshaller() {
        @Override
        public boolean matches(DeclaredType type) {
          // TODO(shahan): refine this as needed by splitting this into separate marshallers.
          return matchesErased(type, Iterable.class)
              || matchesErased(type, Collection.class)
              || matchesErased(type, List.class)
              || matchesErased(type, ImmutableList.class);
        }

        @Override
        public void addSerializationCode(Context context) {
          // Writes the target count to the stream so deserialization knows when to stop.
          context.builder.addStatement("codedOut.writeInt32NoTag($L.size())", context.name);
          Context repeated =
              context.with(
                  (DeclaredType) context.getDeclaredType().getTypeArguments().get(0),
                  context.makeName("repeated"));
          context.builder.beginControlFlow(
              "for ($T $L : $L)", repeated.getTypeName(), repeated.name, context.name);
          writeSerializationCode(repeated);
          context.builder.endControlFlow();
        }

        @Override
        public void addDeserializationCode(Context context) {
          Context repeated =
              context.with(
                  (DeclaredType) context.getDeclaredType().getTypeArguments().get(0),
                  context.makeName("repeated"));
          String builderName = context.makeName("builder");
          context.builder.addStatement(
              "$T<$T> $L = new $T<>()",
              ImmutableList.Builder.class,
              repeated.getTypeName(),
              builderName,
              ImmutableList.Builder.class);
          String lengthName = context.makeName("length");
          context.builder.addStatement("int $L = codedIn.readInt32()", lengthName);
          String indexName = context.makeName("i");
          context.builder.beginControlFlow(
              "for (int $L = 0; $L < $L; ++$L)", indexName, indexName, lengthName, indexName);
          writeDeserializationCode(repeated);
          context.builder.addStatement("$L.add($L)", builderName, repeated.name);
          context.builder.endControlFlow();
          context.builder.addStatement("$L = $L.build()", context.name, builderName);
        }
      };

  private final Marshaller immutableSortedSetMarshaller =
      new Marshaller() {
        @Override
        public boolean matches(DeclaredType type) {
          return matchesErased(type, ImmutableSortedSet.class);
        }

        @Override
        public void addSerializationCode(Context context) {
          listMarshaller.addSerializationCode(context);
        }

        @Override
        public void addDeserializationCode(Context context) {
          Context repeated =
              context.with(
                  (DeclaredType) context.getDeclaredType().getTypeArguments().get(0),
                  context.makeName("repeated"));
          String builderName = context.makeName("builder");
          context.builder.addStatement(
              "$T<$T> $L = new $T<>($T.naturalOrder())",
              ImmutableSortedSet.Builder.class,
              repeated.getTypeName(),
              builderName,
              ImmutableSortedSet.Builder.class,
              Comparator.class);
          String lengthName = context.makeName("length");
          context.builder.addStatement("int $L = codedIn.readInt32()", lengthName);
          String indexName = context.makeName("i");
          context.builder.beginControlFlow(
              "for (int $L = 0; $L < $L; ++$L)", indexName, indexName, lengthName, indexName);
          writeDeserializationCode(repeated);
          context.builder.addStatement("$L.add($L)", builderName, repeated.name);
          context.builder.endControlFlow();
          context.builder.addStatement("$L = $L.build()", context.name, builderName);
        }
      };

  private final Marshaller mapMarshaller =
      new Marshaller() {
        @Override
        public boolean matches(DeclaredType type) {
          return matchesErased(type, Map.class);
        }

        @Override
        public void addSerializationCode(Context context) {
          context.builder.addStatement("codedOut.writeInt32NoTag($L.size())", context.name);
          String mapName = context.makeName("orderedMap");
          String entryName = context.makeName("entry");
          Context key =
              context.with(
                  (DeclaredType) context.getDeclaredType().getTypeArguments().get(0),
                  entryName + ".getKey()");
          Context value =
              context.with(
                  (DeclaredType) context.getDeclaredType().getTypeArguments().get(1),
                  entryName + ".getValue()");
          context.builder.addStatement(
              "$T<$T, $T> $L = null", Map.class, key.getTypeName(), value.getTypeName(), mapName);
          context.builder.beginControlFlow("if ($L instanceof $T)", context.name, SortedMap.class);
          context.builder.addStatement("$L = $L", mapName, context.name);
          context.builder.nextControlFlow("else");
          context.builder.addStatement("$L = new $T<>($L)", mapName, TreeMap.class, context.name);
          context.builder.endControlFlow();
          context.builder.beginControlFlow(
              "for ($T<$T, $T> $L : $L.entrySet())",
              Map.Entry.class,
              key.getTypeName(),
              value.getTypeName(),
              entryName,
              mapName);
          writeSerializationCode(key);
          writeSerializationCode(value);
          context.builder.endControlFlow();
        }

        @Override
        public void addDeserializationCode(Context context) {
          addMapDeserializationCode(context, context.name, false);
        }
      };

  private final Marshaller immutableMapMarshaller =
      new Marshaller() {
        @Override
        public boolean matches(DeclaredType type) {
          // TODO(shahan): refine as needed by splitting these into separate marshallers.
          return matchesErased(type, ImmutableMap.class)
              || matchesErased(type, ImmutableSortedMap.class);
        }

        @Override
        public void addSerializationCode(Context context) {
          mapMarshaller.addSerializationCode(context);
        }

        @Override
        public void addDeserializationCode(Context context) {
          String builderName = context.makeName("builder");
          addMapDeserializationCode(context, builderName, true);
          context.builder.addStatement("$L = $L.build()", context.name, builderName);
        }
      };

  /** Helper for mapMarshaller and immutableMapMarshaller. */
  private void addMapDeserializationCode(
      Context context, String builderName, boolean isImmutableMap) {
          Context key =
              context.with(
                  (DeclaredType) context.getDeclaredType().getTypeArguments().get(0),
                  context.makeName("key"));
          Context value =
              context.with(
                  (DeclaredType) context.getDeclaredType().getTypeArguments().get(1),
                  context.makeName("value"));
    if (isImmutableMap) {
      context.builder.addStatement(
          "$T<$T, $T> $L = new $T<>($T.naturalOrder())",
          ImmutableSortedMap.Builder.class,
          key.getTypeName(),
          value.getTypeName(),
          builderName,
          ImmutableSortedMap.Builder.class,
          Comparator.class);
    } else {
      context.builder.addStatement("$L = new $T<>()", builderName, TreeMap.class);
    }
    String lengthName = context.makeName("length");
    context.builder.addStatement("int $L = codedIn.readInt32()", lengthName);
    String indexName = context.makeName("i");
    context.builder.beginControlFlow(
        "for (int $L = 0; $L < $L; ++$L)", indexName, indexName, lengthName, indexName);
    writeDeserializationCode(key);
    writeDeserializationCode(value);
    context.builder.addStatement("$L.put($L, $L)", builderName, key.name, value.name);
    context.builder.endControlFlow();
  }

  private final Marshaller multimapMarshaller =
      new Marshaller() {
        @Override
        public boolean matches(DeclaredType type) {
          return matchesErased(type, ImmutableMultimap.class)
              || matchesErased(type, ImmutableListMultimap.class);
        }

        @Override
        public void addSerializationCode(Context context) {
          context.builder.addStatement("codedOut.writeInt32NoTag($L.size())", context.name);
          String entryName = context.makeName("entry");
          Context key =
              context.with(
                  (DeclaredType) context.getDeclaredType().getTypeArguments().get(0),
                  entryName + ".getKey()");
          Context value =
              context.with(
                  (DeclaredType) context.getDeclaredType().getTypeArguments().get(1),
                  entryName + ".getValue()");
          context.builder.beginControlFlow(
              "for ($T<$T, $T> $L : $L.entries())",
              Map.Entry.class,
              key.getTypeName(),
              value.getTypeName(),
              entryName,
              context.name);
          writeSerializationCode(key);
          writeSerializationCode(value);
          context.builder.endControlFlow();
        }

        @Override
        public void addDeserializationCode(Context context) {
          Context key =
              context.with(
                  (DeclaredType) context.getDeclaredType().getTypeArguments().get(0),
                  context.makeName("key"));
          Context value =
              context.with(
                  (DeclaredType) context.getDeclaredType().getTypeArguments().get(1),
                  context.makeName("value"));
          String builderName = context.makeName("builder");
          context.builder.addStatement(
              "$T<$T, $T> $L = new $T<>()",
              ImmutableListMultimap.Builder.class,
              key.getTypeName(),
              value.getTypeName(),
              builderName,
              ImmutableListMultimap.Builder.class);
          String lengthName = context.makeName("length");
          context.builder.addStatement("int $L = codedIn.readInt32()", lengthName);
          String indexName = context.makeName("i");
          context.builder.beginControlFlow(
              "for (int $L = 0; $L < $L; ++$L)", indexName, indexName, lengthName, indexName);
          writeDeserializationCode(key);
          writeDeserializationCode(value);
          context.builder.addStatement("$L.put($L, $L)", builderName, key.name, value.name);
          context.builder.endControlFlow();
          context.builder.addStatement("$L = $L.build()", context.name, builderName);
        }
      };

  /** Since we cannot add a codec to {@link Pattern}, it needs to be supported natively. */
  private final Marshaller patternMarshaller =
      new Marshaller() {
        @Override
        public boolean matches(DeclaredType type) {
          return matchesType(type, Pattern.class);
        }

        @Override
        public void addSerializationCode(Context context) {
          context.builder.addStatement(
              "$T.asciiOptimized().serialize($L.pattern(), codedOut)",
              StringCodecs.class,
              context.name);
          context.builder.addStatement("codedOut.writeInt32NoTag($L.flags())", context.name);
        }

        @Override
        public void addDeserializationCode(Context context) {
          context.builder.addStatement(
              "$L = $T.compile($T.asciiOptimized().deserialize(codedIn), codedIn.readInt32())",
              context.name,
              Pattern.class,
              StringCodecs.class);
        }
      };

  /** Since we cannot add a codec to {@link HashCode}, it needs to be supported natively. */
  private final Marshaller hashCodeMarshaller =
      new Marshaller() {
        @Override
        public boolean matches(DeclaredType type) {
          return matchesType(type, HashCode.class);
        }

        @Override
        public void addSerializationCode(Context context) {
          context.builder.addStatement("codedOut.writeByteArrayNoTag($L.asBytes())", context.name);
        }

        @Override
        public void addDeserializationCode(Context context) {
          context.builder.addStatement(
              "$L = $T.fromBytes(codedIn.readByteArray())", context.name, HashCode.class);
        }
      };

  private final Marshaller protoMarshaller =
      new Marshaller() {
        @Override
        public boolean matches(DeclaredType type) {
          return isSubtype(type, AbstractMessage.class);
        }

        @Override
        public void addSerializationCode(Context context) {
          context.builder.addStatement("codedOut.writeMessageNoTag($L)", context.name);
        }

        @Override
        public void addDeserializationCode(Context context) {
          String builderName = context.makeName("builder");
          context.builder.addStatement(
              "$T.Builder $L = $T.newBuilder()",
              context.getTypeName(),
              builderName,
              context.getTypeName());
          context.builder.addStatement(
              "codedIn.readMessage($L, $T.getEmptyRegistry())",
              builderName,
              ExtensionRegistryLite.class);
          context.builder.addStatement("$L = $L.build()", context.name, builderName);
        }
      };

  private final Marshaller codecMarshaller =
      new Marshaller() {
        @Override
        public boolean matches(DeclaredType type) {
          return getCodec(type).isPresent();
        }

        @Override
        public void addSerializationCode(Context context) {
          TypeMirror codecType = getCodec(context.getDeclaredType()).get().asType();
          if (isSubtypeErased(codecType, ObjectCodec.class)) {
            context.builder.addStatement(
                "$T.CODEC.serialize($L, codedOut)", context.getTypeName(), context.name);
          } else if (isSubtypeErased(codecType, InjectingObjectCodec.class)) {
            context.builder.addStatement(
                "$T.CODEC.serialize(dependency, $L, codedOut)",
                context.getTypeName(),
                context.name);
          } else {
            throw new IllegalArgumentException(
                "CODEC field of "
                    + ((TypeElement) context.getDeclaredType().asElement()).getQualifiedName()
                    + " is neither ObjectCodec nor InjectingCodec");
          }
        }

        @Override
        public void addDeserializationCode(Context context) {
          TypeMirror codecType = getCodec(context.getDeclaredType()).get().asType();
          if (isSubtypeErased(codecType, ObjectCodec.class)) {
            context.builder.addStatement(
                "$L = $T.CODEC.deserialize(codedIn)", context.name, context.getTypeName());
          } else if (isSubtypeErased(codecType, InjectingObjectCodec.class)) {
            context.builder.addStatement(
                "$L = $T.CODEC.deserialize(dependency, codedIn)",
                context.name,
                context.getTypeName());
          } else {
            throw new IllegalArgumentException(
                "CODEC field of "
                    + ((TypeElement) context.getDeclaredType().asElement()).getQualifiedName()
                    + " is neither ObjectCodec nor InjectingCodec");
          }
        }
      };

  private final ImmutableList<PrimitiveValueSerializationCodeGenerator> primitiveGenerators =
      ImmutableList.of(intCodeGenerator);

  private final ImmutableList<Marshaller> marshallers =
      ImmutableList.of(
          enumMarshaller,
          stringMarshaller,
          optionalMarshaller,
          mapEntryMarshaller,
          listMarshaller,
          immutableSortedSetMarshaller,
          mapMarshaller,
          immutableMapMarshaller,
          multimapMarshaller,
          patternMarshaller,
          hashCodeMarshaller,
          protoMarshaller,
          codecMarshaller);

  /** True when {@code type} has the same type as {@code clazz}. */
  private boolean matchesType(TypeMirror type, Class<?> clazz) {
    return env.getTypeUtils().isSameType(type, getType(clazz));
  }

  /** True when {@code type} is a subtype of {@code clazz}. */
  private boolean isSubtype(TypeMirror type, Class<?> clazz) {
    return env.getTypeUtils().isSubtype(type, getType(clazz));
  }

  /** True when erasure of {@code type} matches erasure of {@code clazz}. */
  private boolean matchesErased(TypeMirror type, Class<?> clazz) {
    return env.getTypeUtils()
        .isSameType(env.getTypeUtils().erasure(type), env.getTypeUtils().erasure(getType(clazz)));
  }

  /** True when erasure of {@code type} is a subtype of the erasure of {@code clazz}. */
  private boolean isSubtypeErased(TypeMirror type, Class<?> clazz) {
    return env.getTypeUtils()
        .isSubtype(env.getTypeUtils().erasure(type), env.getTypeUtils().erasure(getType(clazz)));
  }

  /** Returns the TypeMirror corresponding to {@code clazz}. */
  private TypeMirror getType(Class<?> clazz) {
    return env.getElementUtils().getTypeElement((clazz.getCanonicalName())).asType();
  }

  private static java.util.Optional<? extends Element> getCodec(DeclaredType type) {
    return type.asElement()
        .getEnclosedElements()
        .stream()
        .filter(t -> t.getModifiers().contains(Modifier.STATIC))
        .filter(t -> t.getSimpleName().contentEquals("CODEC"))
        .filter(t -> t.getKind() == ElementKind.FIELD)
        .findAny();
  }
}
