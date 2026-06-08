package io.safelang.compiler.c;

import io.safelang.ast.ASTNode;
import io.safelang.ast.IfExpressionNode;
import io.safelang.ast.LiteralNode;
import java.util.List;

/**
 * C code emission for SAFE builtin functions.
 *
 * <p>Dispatches on the builtin name (~70 cases) and emits the equivalent call into the C runtime
 * exposed by {@code safe_runtime.h}. Many builtins are type-aware (collection element boxing,
 * key-type-keyed map ops) and delegate type inference back to the surrounding visitor through
 * {@link CBuiltinContext}.
 *
 * <p>Stateless apart from the injected context.
 */
final class CBuiltinResolver {

  private final CBuiltinContext context;

  CBuiltinResolver(final CBuiltinContext context) {
    this.context = context;
  }

  /** Generate the C expression for a builtin call, or {@code null} if {@code name} is unknown. */
  String resolve(final String name, final List<ASTNode> arguments) {
    switch (name) {
      case "println":
        return println(arguments);
      case "print":
        return print(arguments);
      case "len":
        {
          if (!arguments.isEmpty()) {
            final var arg = context.emit(arguments.getFirst());
            final var type = context.infer(arguments.getFirst());
            if ("string".equals(type)) return "safe_string_len(" + arg + ")";
            if ("bytes".equals(type)) return "safe_bytes_len(" + arg + ")";
            if (type != null && type.startsWith("map<")) return "safe_map_len(" + arg + ")";
            if (type != null && type.startsWith("set<")) return "safe_set_len(" + arg + ")";
            return "safe_list_len(" + arg + ")";
          }
          return null;
        }
      case "str":
        {
          if (!arguments.isEmpty()) {
            final var arg = context.emit(arguments.getFirst());
            final var type = context.infer(arguments.getFirst());
            if ("void".equals(type)) {
              if (arguments.getFirst() instanceof IfExpressionNode conditional
                  && !conditional.hasOtherwise()) {
                return arg;
              }
              return "\"void\"";
            }
            if ("string".equals(type)) return arg;
            if ("bytes".equals(type)) return "safe_bytes_tostr(" + arg + ")";
            if ("float".equals(type)) return "safe_string_val_float(" + arg + ")";
            if ("boolean".equals(type)) return "safe_string_val_bool(" + arg + ")";
            final var kind = listKind(type);
            if (kind >= 0) return "safe_list_to_string(" + arg + ", " + kind + ")";
            if (context.stringifiable(type)) return context.stringify(arg, type);
            return "safe_string_val(" + arg + ")";
          }
          return null;
        }
      case "int":
      case "integer":
        {
          if (!arguments.isEmpty()) {
            final var arg = context.emit(arguments.getFirst());
            final var type = context.infer(arguments.getFirst());
            if ("float".equals(type)) return "(int64_t)(" + arg + ")";
            if ("int".equals(type) || "uint".equals(type)) return arg;
            return "safe_int_val(" + arg + ")";
          }
          return null;
        }
      case "decimal":
        {
          if (!arguments.isEmpty()) {
            final var arg = context.emit(arguments.getFirst());
            final var type = context.infer(arguments.getFirst());
            if ("int".equals(type) || "uint".equals(type)) return "(double)(" + arg + ")";
            if ("float".equals(type)) return arg;
            return "safe_float_val(" + arg + ")";
          }
          return null;
        }
      case "range":
        {
          if (arguments.size() >= 3) {
            final var start = context.emit(arguments.getFirst());
            final var end = context.emit(arguments.get(1));
            final var step = context.emit(arguments.get(2));
            return "safe_range_step(" + start + ", " + end + ", " + step + ")";
          } else if (arguments.size() == 2) {
            final var start = context.emit(arguments.getFirst());
            final var end = context.emit(arguments.get(1));
            return "safe_range(" + start + ", " + end + ")";
          } else if (arguments.size() == 1) {
            final var end = context.emit(arguments.getFirst());
            return "safe_range(0, " + end + ")";
          }
          return null;
        }
      case "sqrt":
        return "sqrt(" + context.emit(arguments.getFirst()) + ")";
      case "pow":
        return "pow("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ")";
      case "abs":
        {
          final var arg = context.emit(arguments.getFirst());
          final var type = context.infer(arguments.getFirst());
          if ("int".equals(type) || "uint".equals(type)) {
            return "((" + arg + ") < 0 ? -(" + arg + ") : (" + arg + "))";
          }
          return "fabs((double)" + arg + ")";
        }
      case "min":
        return "safe_min("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ")";
      case "max":
        return "safe_max("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ")";
      case "floor":
        return "(int64_t)floor((double)" + context.emit(arguments.getFirst()) + ")";
      case "ceil":
        return "(int64_t)ceil((double)" + context.emit(arguments.getFirst()) + ")";
      case "round":
        return "(int64_t)round((double)" + context.emit(arguments.getFirst()) + ")";
      case "log":
        return "log(" + context.emit(arguments.getFirst()) + ")";
      case "sin":
        return "sin(" + context.emit(arguments.getFirst()) + ")";
      case "cos":
        return "cos(" + context.emit(arguments.getFirst()) + ")";
      case "tan":
        return "tan(" + context.emit(arguments.getFirst()) + ")";
      case "asin":
        return "asin(" + context.emit(arguments.getFirst()) + ")";
      case "acos":
        return "acos(" + context.emit(arguments.getFirst()) + ")";
      case "atan":
        return "atan(" + context.emit(arguments.getFirst()) + ")";
      case "atan2":
        return "atan2("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ")";
      case "exp":
        return "exp(" + context.emit(arguments.getFirst()) + ")";
      case "log10":
        return "log10(" + context.emit(arguments.getFirst()) + ")";
      case "rand":
        return "((double)rand() / RAND_MAX)";
      case "randint":
        {
          final var low = context.emit(arguments.getFirst());
          final var high = context.emit(arguments.get(1));
          return "({int64_t __lo__="
              + low
              + "; int64_t __hi__="
              + high
              + "; int64_t __range__=__hi__-__lo__; __range__<=0 ? __lo__ : __lo__ + rand() % __range__;})";
        }
      case "seed":
        return "srand((unsigned int)" + context.emit(arguments.getFirst()) + ")";
      case "matches":
        return "safe_regex_matches("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ")";
      case "findall":
        return "safe_regex_find("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ")";
      case "replaceall":
        return "safe_regex_replace("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ", "
            + context.emit(arguments.get(2))
            + ")";
      case "listdir":
        return "safe_listdir(" + context.emit(arguments.getFirst()) + ")";
      case "mkdir":
        return "safe_mkdir(" + context.emit(arguments.getFirst()) + ")";
      case "rmdir":
        return "safe_rmdir(" + context.emit(arguments.getFirst()) + ")";
      case "isdir":
        return "safe_isdir(" + context.emit(arguments.getFirst()) + ")";
      case "substring":
        return "safe_substring("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ", "
            + context.emit(arguments.get(2))
            + ")";
      case "indexOf":
        return "safe_indexof("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ")";
      case "charAt":
        return "safe_charat("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ")";
      case "trim":
        return "safe_trim(" + context.emit(arguments.getFirst()) + ")";
      case "upper":
        return "safe_upper(" + context.emit(arguments.getFirst()) + ")";
      case "lower":
        return "safe_lower(" + context.emit(arguments.getFirst()) + ")";
      case "replace":
        return "safe_replace("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ", "
            + context.emit(arguments.get(2))
            + ")";
      case "starts":
        return "safe_starts("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ")";
      case "ends":
        return "safe_ends("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ")";
      case "float":
        return "(double)" + context.emit(arguments.getFirst());
      case "chars":
        {
          if (!arguments.isEmpty()) {
            final var arg = context.emit(arguments.getFirst());
            return "safe_string_chars(" + arg + ")";
          }
          return null;
        }
      case "split":
        {
          if (arguments.size() >= 2) {
            return "safe_string_split("
                + context.emit(arguments.getFirst())
                + ", "
                + context.emit(arguments.get(1))
                + ")";
          }
          return null;
        }
      case "join":
        {
          if (arguments.size() >= 2) {
            return "safe_string_join("
                + context.emit(arguments.getFirst())
                + ", "
                + context.emit(arguments.get(1))
                + ")";
          }
          return null;
        }
      case "size":
        {
          if (!arguments.isEmpty()) {
            final var arg = context.emit(arguments.getFirst());
            final var type = context.infer(arguments.getFirst());
            if (type != null && type.startsWith("map<")) return "safe_map_len(" + arg + ")";
            if (type != null && type.startsWith("set<")) return "safe_set_len(" + arg + ")";
            return "safe_list_len(" + arg + ")";
          }
          return null;
        }
      case "add":
        {
          if (arguments.size() >= 2) {
            final var first = context.infer(arguments.getFirst());
            if (first != null && first.startsWith("set<")) {
              final var target = context.emit(arguments.getFirst());
              final var value = context.emit(arguments.get(1));
              final var kind = context.infer(arguments.get(1));
              return "safe_set_add(" + target + ", " + context.wrap(value, kind) + ")";
            }
          }
          return null;
        }
      case "keys":
        {
          if (!arguments.isEmpty()) {
            final var type = context.infer(arguments.getFirst());
            if (type != null && type.startsWith("map<")) {
              return "safe_map_keys(" + context.emit(arguments.getFirst()) + ")";
            }
          }
          return null;
        }
      case "values":
        {
          if (!arguments.isEmpty()) {
            final var type = context.infer(arguments.getFirst());
            if (type != null && type.startsWith("map<")) {
              final var arg = context.emit(arguments.getFirst());
              final var stored = context.valued(type);
              if (context.isPointerType(stored)) return "safe_map_values_ptr(" + arg + ")";
              return switch (stored) {
                case "float" -> "safe_map_values_float(" + arg + ")";
                case "string" -> "safe_map_values_str(" + arg + ")";
                case "boolean" -> "safe_map_values_bool(" + arg + ")";
                default -> "safe_map_values_int(" + arg + ")";
              };
            }
          }
          return null;
        }
      case "contains":
        {
          if (arguments.size() >= 2) {
            final var first = context.infer(arguments.getFirst());
            if (first != null && first.startsWith("list<")) {
              final var target = context.emit(arguments.getFirst());
              final var value = context.emit(arguments.get(1));
              final var kind = context.infer(arguments.get(1));
              if ("float".equals(kind)) {
                return "safe_list_contains_float(" + target + ", " + value + ")";
              } else if ("string".equals(kind)) {
                return "safe_list_contains_str(" + target + ", " + value + ")";
              }
              return "safe_list_contains_int(" + target + ", " + value + ")";
            }
            if (first != null && first.startsWith("set<")) {
              final var target = context.emit(arguments.getFirst());
              final var value = context.emit(arguments.get(1));
              final var kind = context.infer(arguments.get(1));
              return "safe_set_contains(" + target + ", " + context.wrap(value, kind) + ")";
            }
            if (first != null && first.startsWith("map<")) {
              final var target = context.emit(arguments.getFirst());
              final var element = context.emit(arguments.get(1));
              final var key = context.keyed(first);
              if (context.isIntegerKeyed(key)) {
                return "safe_map_contains_ikey(" + target + ", " + element + ")";
              }
              if (context.isFloatKeyed(key)) {
                return "safe_map_contains_fkey(" + target + ", " + element + ")";
              }
              return "safe_map_contains(" + target + ", " + element + ")";
            }
          }
          return null;
        }
      case "remove":
        {
          if (arguments.size() >= 2) {
            final var first = context.infer(arguments.getFirst());
            if (first != null && first.startsWith("map<")) {
              final var target = context.emit(arguments.getFirst());
              final var element = context.emit(arguments.get(1));
              final var key = context.keyed(first);
              if (context.isIntegerKeyed(key)) {
                return "safe_map_ikey_remove(" + target + ", " + element + ")";
              }
              if (context.isFloatKeyed(key)) {
                return "safe_map_fkey_remove(" + target + ", " + element + ")";
              }
              return "safe_map_remove(" + target + ", " + element + ")";
            }
            if (first != null && first.startsWith("list<")) {
              final var target = context.emit(arguments.getFirst());
              final var index = context.emit(arguments.get(1));
              return "safe_list_remove_at(" + target + ", " + index + ")";
            }
          }
          return null;
        }
      case "slice":
        {
          if (arguments.size() >= 3) {
            final var target = context.emit(arguments.getFirst());
            final var start = context.emit(arguments.get(1));
            final var end = context.emit(arguments.get(2));
            final var type = context.infer(arguments.getFirst());
            if ("bytes".equals(type)) {
              return "safe_bytes_slice(" + target + ", " + start + ", " + end + ")";
            }
            return "safe_list_slice(" + target + ", " + start + ", " + end + ")";
          }
          return null;
        }
      case "reverse":
        {
          if (!arguments.isEmpty()) {
            final var arg = context.emit(arguments.getFirst());
            final var type = context.infer(arguments.getFirst());
            if ("string".equals(type)) {
              return "safe_string_reverse(" + arg + ")";
            }
            return "safe_list_reverse(" + arg + ")";
          }
          return null;
        }
      case "union":
        {
          if (arguments.size() >= 2) {
            final var first = context.infer(arguments.getFirst());
            if (first != null && first.startsWith("set<")) {
              return "safe_set_union("
                  + context.emit(arguments.getFirst())
                  + ", "
                  + context.emit(arguments.get(1))
                  + ")";
            }
          }
          return null;
        }
      case "intersect":
        {
          if (arguments.size() >= 2) {
            final var first = context.infer(arguments.getFirst());
            if (first != null && first.startsWith("set<")) {
              return "safe_set_intersect("
                  + context.emit(arguments.getFirst())
                  + ", "
                  + context.emit(arguments.get(1))
                  + ")";
            }
          }
          return null;
        }
      case "difference":
        {
          if (arguments.size() >= 2) {
            final var first = context.infer(arguments.getFirst());
            if (first != null && first.startsWith("set<")) {
              return "safe_set_difference("
                  + context.emit(arguments.getFirst())
                  + ", "
                  + context.emit(arguments.get(1))
                  + ")";
            }
          }
          return null;
        }
      case "typeof":
        {
          if (!arguments.isEmpty()) {
            final var type = context.infer(arguments.getFirst());
            if (type != null) {
              if (type.startsWith("list<")) return "\"list\"";
              if (type.startsWith("map<")) return "\"map\"";
              if (type.startsWith("set<")) return "\"set\"";
              if (type.startsWith("tuple<")) return "\"tuple\"";
              return "\"" + type + "\"";
            }
          }
          return "\"unknown\"";
        }
      case "time":
        return "safe_time()";
      case "exit":
        return "exit((int)" + context.emit(arguments.getFirst()) + ")";
      case "input":
        return "safe_input(" + context.emit(arguments.getFirst()) + ")";
      case "args":
        return "safe_args()";
        // Binary bytes builtins
      case "balloc":
        return "safe_bytes_new(" + context.emit(arguments.getFirst()) + ")";
      case "bget":
        return "safe_bytes_get("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ")";
      case "bset":
        return "safe_bytes_set("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ", "
            + context.emit(arguments.get(2))
            + ")";
      case "bslice":
        return "safe_bytes_slice("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ", "
            + context.emit(arguments.get(2))
            + ")";
      case "bconcat":
        return "safe_bytes_concat("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ")";
      case "bencode":
        return "safe_bytes_encode(" + context.emit(arguments.getFirst()) + ")";
      case "bdecode":
        return "safe_bytes_decode(" + context.emit(arguments.getFirst()) + ")";
      case "bpack":
        return "safe_bytes_pack("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ")";
      case "bunpack":
        return "safe_bytes_unpack("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ", "
            + context.emit(arguments.get(2))
            + ")";
      case "bpatch":
        return "safe_bytes_patch("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ", "
            + context.emit(arguments.get(2))
            + ")";
      case "bcompare":
        return "safe_bytes_compare("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ")";
      case "bhex":
        return "safe_bytes_hex(" + context.emit(arguments.getFirst()) + ")";
        // Binary file I/O builtins
      case "bopen":
        return "safe_bopen("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ")";
      case "bclose":
        return "safe_bclose(" + context.emit(arguments.getFirst()) + ")";
      case "bread":
        return "safe_bread("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ")";
      case "bwrite":
        return "safe_bwrite("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ")";
      case "bseek":
        return "safe_bseek("
            + context.emit(arguments.getFirst())
            + ", "
            + context.emit(arguments.get(1))
            + ")";
      case "bsize":
        return "safe_bsize(" + context.emit(arguments.getFirst()) + ")";
      case "bflush":
        return "safe_bflush(" + context.emit(arguments.getFirst()) + ")";
        // Hash builtins
      case "fnv":
        return "safe_hash_fnv(" + context.emit(arguments.getFirst()) + ")";
      case "crc":
        return "safe_hash_crc(" + context.emit(arguments.getFirst()) + ")";
      case "murmur":
        return "safe_hash_murmur(" + context.emit(arguments.getFirst()) + ")";
        // Collection builtins
      case "append":
        {
          final var list = context.emit(arguments.getFirst());
          final var value = context.emit(arguments.get(1));
          final var kind = context.infer(arguments.get(1));
          if ("int".equals(kind) || "uint".equals(kind) || context.isGenericType(kind)) {
            return "safe_list_append_copy_int(" + list + ", (int64_t)" + value + ")";
          }
          if (kind != null && context.enumerations().containsKey(kind)) {
            return "({ "
                + kind
                + "* __tmp = safe_alloc(sizeof("
                + kind
                + "), SAFE_KIND_ENUM, 0); *__tmp = "
                + value
                + "; safe_list_append_copy("
                + list
                + ", __tmp); })";
          }
          if (kind != null && kind.startsWith("tuple<")) {
            // Phase 7: compute the heap-field bitmap for the tuple so
            // safe_dispose releases refcounted fields. If we can see the
            // tuple's literal form, use its per-element types; otherwise
            // fall through to 0 (no auto-release — legacy behaviour).
            int bits = 0;
            if (arguments.get(1) instanceof io.safelang.ast.TupleLiteralNode tlit) {
              final int count = Math.min(tlit.elements().size(), 8);
              for (int i = 0; i < count; i++) {
                final var t = context.infer(tlit.elements().get(i));
                if (!"0".equals(context.safeKindOf(t))) bits |= (1 << i);
              }
            }
            return "({ SAFETuple* __tmp = (SAFETuple*)safe_alloc(sizeof(SAFETuple), SAFE_KIND_TUPLE, "
                + bits
                + "); *__tmp = "
                + value
                + "; safe_list_append_copy("
                + list
                + ", __tmp); })";
          }
          return "safe_list_append_copy(" + list + ", " + value + ")";
        }
      case "sort":
        return "safe_list_sort(" + context.emit(arguments.getFirst()) + ")";
        // File builtins
      case "read":
        {
          if (arguments.size() == 1)
            return "safe_rawread(" + context.emit(arguments.getFirst()) + ")";
          return null; // binary:read(handle, size) handled elsewhere
        }
      case "write":
        {
          // file:write(string path, string content) — path-based one-shot
          // write. Without this case the fallback emits raw `write(p, c)`
          // which collides with POSIX <unistd.h> and gcc errors out.
          if (arguments.size() == 2) {
            final var path = context.emit(arguments.getFirst());
            final var content = context.emit(arguments.get(1));
            return "safe_write(" + path + ", " + content + ")";
          }
          return null; // binary:write(handle, bytes) handled elsewhere
        }
      case "appendfile":
        {
          final var path = context.emit(arguments.getFirst());
          final var content = context.emit(arguments.get(1));
          return "safe_append(" + path + ", " + content + ")";
        }
      case "lines":
        {
          // file:lines(string path) — path-based line reader.
          if (arguments.size() == 1) {
            return "safe_pathlines(" + context.emit(arguments.getFirst()) + ")";
          }
          return null;
        }
      case "delete":
        return "safe_delete(" + context.emit(arguments.getFirst()) + ")";
      case "exists":
        return "safe_exists(" + context.emit(arguments.getFirst()) + ")";
      case "getenv":
        return "safe_getenv(" + context.emit(arguments.getFirst()) + ")";
      case "fileopen":
        {
          final var path = context.emit(arguments.getFirst());
          final var mode = context.emit(arguments.get(1));
          return "({ int64_t __h__ = safe_fopen("
              + path
              + ", "
              + mode
              + "); "
              + "(__h__ >= 0) ? OpenResult_Ok_new((File){.id = __h__, .path = "
              + path
              + "}) "
              + ": OpenResult_Err_new(\"open failed\"); })";
        }
      case "fileclose":
        return "safe_fclose(" + context.emit(arguments.getFirst()) + ")";
      case "fileread":
        {
          final var arg = context.emit(arguments.getFirst());
          return "({ char* __r__ = safe_fread("
              + arg
              + "); "
              + "__r__ ? ReadResult_Ok_new(__r__) : ReadResult_Err_new(\"read failed\"); })";
        }
      case "filewrite":
        {
          final var handle = context.emit(arguments.getFirst());
          final var content = context.emit(arguments.get(1));
          return "({ safe_fwrite(" + handle + ", " + content + "); WriteResult_Done_new(); })";
        }
      case "fileload":
        {
          final var path = context.emit(arguments.getFirst());
          return "({ char* __r__ = safe_read("
              + path
              + "); "
              + "__r__ ? ReadResult_Ok_new(__r__) : ReadResult_Err_new(\"read failed\"); })";
        }
      case "filesave":
        {
          final var path = context.emit(arguments.getFirst());
          final var content = context.emit(arguments.get(1));
          return "({ safe_write(" + path + ", " + content + "); WriteResult_Done_new(); })";
        }
      case "filereadlines":
        {
          final var arg = context.emit(arguments.getFirst());
          return "LinesResult_Ok_new(safe_flines(" + arg + "))";
        }
      case "filevalid":
        return "safe_fvalid(" + context.emit(arguments.getFirst()) + ")";
      default:
        return null;
    }
  }

  private String println(final List<ASTNode> args) {
    if (args.isEmpty()) {
      return "printf(\"\\n\")";
    }
    final var arg = args.getFirst();
    if (arg instanceof LiteralNode.StringLiteral lit) {
      return "printf(\"" + context.escape(lit.value()) + "\\n\")";
    }
    final var emitted = context.emit(arg);
    // Boolean values should print as "true"/"false" not "1"/"0"
    if (context.isBooleanExpression(arg)) {
      return "printf(\"%s\\n\", safe_string_val_bool(" + emitted + "))";
    }
    // Floats must render like the interpreter (Double.toString), not printf's "%g".
    if ("float".equals(context.infer(arg))) {
      return "printf(\"%s\\n\", safe_string_val_float(" + emitted + "))";
    }
    final var kind = listKind(context.infer(arg));
    if (kind >= 0) {
      return "safe_println_str(safe_list_to_string(" + emitted + ", " + kind + "))";
    }
    final var type = context.infer(arg);
    if (context.stringifiable(type)) {
      return "safe_println_str(" + context.stringify(emitted, type) + ")";
    }
    final var fmt = context.format(arg);
    return "printf(\"" + fmt + "\\n\", " + emitted + ")";
  }

  private String print(final List<ASTNode> args) {
    if (args.isEmpty()) {
      return "printf(\"\")";
    }
    final var arg = args.getFirst();
    if (arg instanceof LiteralNode.StringLiteral lit) {
      return "printf(\"" + context.escape(lit.value()) + "\")";
    }
    final var emitted = context.emit(arg);
    // Boolean values should print as "true"/"false" not "1"/"0"
    if (context.isBooleanExpression(arg)) {
      return "printf(\"%s\", safe_string_val_bool(" + emitted + "))";
    }
    // Floats must render like the interpreter (Double.toString), not printf's "%g".
    if ("float".equals(context.infer(arg))) {
      return "printf(\"%s\", safe_string_val_float(" + emitted + "))";
    }
    final var kind = listKind(context.infer(arg));
    if (kind >= 0) {
      return "safe_print_str(safe_list_to_string(" + emitted + ", " + kind + "))";
    }
    final var type = context.infer(arg);
    if (context.stringifiable(type)) {
      return "safe_print_str(" + context.stringify(emitted, type) + ")";
    }
    final var fmt = context.format(arg);
    return "printf(\"" + fmt + "\", " + emitted + ")";
  }

  /**
   * Element kind for {@code safe_list_to_string} when {@code type} is a list of a scalar element
   * (0=int, 1=float, 2=string, 3=bool, 4=uint), or {@code -1} when the type is not a scalar list —
   * lists of objects, enums, tuples, maps, or nested lists are not stringifiable in the C backend.
   */
  private static int listKind(final String type) {
    if (type == null || !type.startsWith("list<") || !type.endsWith(">")) {
      return -1;
    }
    return switch (type.substring(5, type.length() - 1)) {
      case "int" -> 0;
      case "float" -> 1;
      case "string" -> 2;
      case "boolean" -> 3;
      case "uint" -> 4;
      default -> -1;
    };
  }
}
