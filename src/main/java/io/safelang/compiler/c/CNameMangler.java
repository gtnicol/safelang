package io.safelang.compiler.c;

import java.util.Set;

final class CNameMangler {

  // C keywords (C89/C99/C11) plus stdlib typedefs that leak in via the
  // runtime's #includes and would collide with user identifiers emitted
  // verbatim. See safe_runtime.h for the include list.
  private static final Set<String> RESERVED =
      Set.of(
          // C89/C99/C11 keywords
          "auto",
          "break",
          "case",
          "char",
          "const",
          "continue",
          "default",
          "do",
          "double",
          "else",
          "enum",
          "extern",
          "float",
          "for",
          "goto",
          "if",
          "inline",
          "int",
          "long",
          "register",
          "restrict",
          "return",
          "short",
          "signed",
          "sizeof",
          "static",
          "struct",
          "switch",
          "typedef",
          "union",
          "unsigned",
          "void",
          "volatile",
          "while",
          "_Bool",
          "bool",
          "true",
          "false",
          "_Complex",
          "_Imaginary",
          "_Alignas",
          "_Alignof",
          "_Atomic",
          "_Generic",
          "_Noreturn",
          "_Static_assert",
          "_Thread_local",
          // stdlib typedefs leaked via <stdio.h>, <stdint.h>, <time.h>,
          // <dirent.h>, <sys/stat.h>, <unistd.h>
          "DIR",
          "FILE",
          "NULL",
          "EOF",
          "size_t",
          "ssize_t",
          "time_t",
          "clock_t",
          "off_t",
          "pid_t",
          "uid_t",
          "gid_t",
          "mode_t",
          "dev_t",
          "ino_t",
          "wchar_t",
          "intptr_t",
          "uintptr_t",
          "int8_t",
          "int16_t",
          "int32_t",
          "int64_t",
          "uint8_t",
          "uint16_t",
          "uint32_t",
          "uint64_t");

  String module(final String module, final String name) {
    return "safe__" + module + "_" + name;
  }

  /**
   * Mangle a user-defined function name. Functions are always prefixed with {@code safe_user_} to
   * avoid colliding with libc symbols like {@code write}, {@code read}, {@code time} that are
   * pulled in by the runtime's includes.
   */
  String function(final String name) {
    return "safe_user_" + name;
  }

  /**
   * Mangle a user-defined local identifier (variable, parameter, field, loop variable, pattern
   * binding). Only mangled when it would collide with a C reserved word or stdlib typedef —
   * generated C stays readable otherwise.
   */
  String user(final String name) {
    return RESERVED.contains(name) ? "safe_user_" + name : name;
  }
}
