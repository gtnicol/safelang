package io.safelang;

import io.safelang.ast.*;
import java.util.*;

/** Holds loaded module programs plus their exported (public) declarations. */
public class ModuleRegistry {

  private final Map<String, Map<String, FunctionDeclarationNode>> functions = new LinkedHashMap<>();
  private final Map<String, Map<String, TypeDeclarationNode>> types = new LinkedHashMap<>();
  private final Map<String, Map<String, EnumDeclarationNode>> enums = new LinkedHashMap<>();
  private final Map<String, Map<String, TypeAliasNode>> aliases = new LinkedHashMap<>();
  private final Map<String, Map<String, VariableDeclarationNode>> constants = new LinkedHashMap<>();
  private final Map<String, ProgramNode> programs = new LinkedHashMap<>();

  /** Register a module and index its exported declarations for cross-module lookup. */
  public void register(final String name, final ProgramNode program) {
    programs.put(name, program);
    final Map<String, FunctionDeclarationNode> moduleFunctions = new LinkedHashMap<>();
    final Map<String, TypeDeclarationNode> moduleTypes = new LinkedHashMap<>();
    final Map<String, EnumDeclarationNode> moduleEnums = new LinkedHashMap<>();
    final Map<String, TypeAliasNode> moduleAliases = new LinkedHashMap<>();
    final Map<String, VariableDeclarationNode> moduleConstants = new LinkedHashMap<>();

    for (final var declaration : program.declarations()) {
      switch (declaration) {
        case FunctionDeclarationNode function -> {
          if (function.isPublic()) {
            moduleFunctions.put(function.name(), function);
          }
        }
        case TypeDeclarationNode type -> {
          if (type.isPublic()) {
            moduleTypes.put(type.name(), type);
          }
        }
        case EnumDeclarationNode enumeration -> {
          if (enumeration.isPublic()) {
            moduleEnums.put(enumeration.name(), enumeration);
          }
        }
        case TypeAliasNode alias -> {
          if (alias.isPublic()) {
            moduleAliases.put(alias.name(), alias);
          }
        }
        case VariableDeclarationNode constant -> {
          if (constant.isConstant()) {
            moduleConstants.put(constant.name(), constant);
          }
        }
        default -> {}
      }
    }

    functions.put(name, moduleFunctions);
    types.put(name, moduleTypes);
    enums.put(name, moduleEnums);
    aliases.put(name, moduleAliases);
    constants.put(name, moduleConstants);
  }

  public boolean has(final String module) {
    return programs.containsKey(module);
  }

  public ProgramNode program(final String module) {
    return programs.get(module);
  }

  public FunctionDeclarationNode function(final String module, final String name) {
    final var map = functions.get(module);
    return map != null ? map.get(name) : null;
  }

  public Map<String, FunctionDeclarationNode> functions(final String module) {
    return Collections.unmodifiableMap(functions.getOrDefault(module, Map.of()));
  }

  public Map<String, TypeDeclarationNode> types(final String module) {
    return Collections.unmodifiableMap(types.getOrDefault(module, Map.of()));
  }

  public Map<String, EnumDeclarationNode> enums(final String module) {
    return Collections.unmodifiableMap(enums.getOrDefault(module, Map.of()));
  }

  public Map<String, TypeAliasNode> aliases(final String module) {
    return Collections.unmodifiableMap(aliases.getOrDefault(module, Map.of()));
  }

  public Map<String, VariableDeclarationNode> constants(final String module) {
    return Collections.unmodifiableMap(constants.getOrDefault(module, Map.of()));
  }

  public VariableDeclarationNode constant(final String module, final String name) {
    final var map = constants.get(module);
    return map != null ? map.get(name) : null;
  }

  public Set<String> modules() {
    return Collections.unmodifiableSet(programs.keySet());
  }
}
