# SAFE Bytecode Assembly Language

This document describes the SAFE assembly language (`.safea` files) and the binary format (`.safeb` files), along with
usage of the assembler, decompiler, and bytecode VM.

> **CLI invocation.** Examples use a short `safe <subcommand>` form for readability. The actual command is
> `java -jar target/safe-lang-1.0.0-cli.jar <subcommand>`; alias `safe` to that in your shell if you want the
> short form to work literally.

## Toolchain Overview

The SAFE bytecode toolchain provides four commands that work together:

```
source.safe ──bytecode──▶ program.safeb ──vm──▶ output
                              │                    ▲
                         disassemble               │
                              │                    │
                              ▼                    │
                         program.safea ──assemble──┘
                                                 (via .safeb)
```

| Command                       | Input           | Output       | Description                |
|-------------------------------|-----------------|--------------|----------------------------|
| `safe bytecode file.safe`     | Source code     | `file.safeb` | Compile to bytecode binary |
| `safe vm file.safeb`          | Bytecode binary | stdout       | Execute via stack-based VM |
| `safe disassemble file.safeb` | Bytecode binary | stdout       | Decompile to assembly text |
| `safe assemble file.safea`    | Assembly text   | `file.safeb` | Assemble text to binary    |

The round-trip `disassemble → assemble → vm` is lossless — it produces identical execution results.

---

## Assembly File Format (.safea)

A `.safea` file is a plain-text representation of a compiled SAFE program. It consists of several sections, each
introduced by a dot-directive.

### Comments

Lines starting with `;` are comments. Inline comments (`;` after an instruction) are also supported and ignored by the
assembler.

```
; This is a comment
const_int @0  ; This pushes the value at pool index 0
```

### Version Directive

```
.version 2
```

Declares the bytecode format version. Currently `2`.

### Constants Section

```
.constants
  @0 int 42
  @1 float 3.14
  @2 string "hello world"
  @3 name "myFunction"
```

Defines the constant pool. Each entry has an index (`@N`), a type tag, and a value. The four constant types are:

| Type     | Description                           | Value format        |
|----------|---------------------------------------|---------------------|
| `int`    | 64-bit signed integer                 | Decimal number      |
| `float`  | 64-bit double-precision float         | Decimal with `.`    |
| `string` | UTF-8 string literal                  | Quoted with `"..."` |
| `name`   | Identifier name (for globals, fields) | Quoted with `"..."` |

Strings support the escape sequences `\\`, `\"`, `\n`, and `\t`.

### Types Section

```
.types
  type Point { x: int, y: int }
  type Person { name: string, age: int }
```

Declares struct types. Each type lists its fields with name and type tag. Supported type tags: `int`, `uint`, `float`,
`string`, `bool`, `list`, `map`, `object`, `enum`, `void`.

This section is only present when the program defines struct types.

### Enums Section

```
.enums
  enum Color { Red, Green, Blue }
  enum Shape { Circle(float), Rectangle(float, float), Point }
```

Declares enum types. Each variant may optionally carry typed data fields in parentheses.

This section is only present when the program defines enum types.

### Globals Section

```
.globals
  var counter
  const MAX_SIZE
```

Declares global variables. Each entry is prefixed with `var` (mutable) or `const` (immutable).

### Functions Section

```
.functions

.function add params=2 locals=3
  load_local 0
  load_local 1
  add
  store_local 2
  load_local 2
  return
  push_void
  return
.end
```

Each function begins with `.function name params=N locals=N` and ends with `.end`. The `params` count is how many
arguments the function takes; `locals` is the total number of local variable slots (including parameters).

#### Contracts

Functions may include optional `.requires`, `.ensures`, and/or `.decreases` blocks before the closing `.end`. The
`.requires` and `.ensures` blocks contain bytecode that evaluates a boolean condition. The `.decreases` block evaluates
to a non-negative integer that must strictly decrease on each recursive call:

```
.function abs params=1 locals=2
  ; function body bytecode...
  load_local 1
  return
  push_void
  return
  .requires
    load_local 0
    const_int @3    ; 100
    neg
    cmp_gt
.end
```

The `.requires` bytecode is evaluated before the function body executes, with parameters already loaded into their
slots. If it produces `false`, execution halts with a contract violation error.

The `.ensures` bytecode is evaluated after the function computes its return value, allowing post-condition checks.

### Main Section

```
.main locals=2
  const_str @0
  call 1 1  ; io$println
  pop
  halt
.end
```

The main section contains the top-level program bytecode. It must end with a `halt` instruction. The optional `locals=N`
declares the number of local variable slots used by main.

---

## Instruction Syntax

Each instruction is written on its own line with the mnemonic followed by operands separated by spaces:

```
mnemonic [operand1] [operand2] [operand3]
```

### Operand Formats

| Format  | Meaning                              | Example        |
|---------|--------------------------------------|----------------|
| `@N`    | Constant pool index                  | `const_int @5` |
| `N`     | Numeric literal (slot, index, count) | `load_local 3` |
| `LNAME` | Label reference (for jumps)          | `jump L42`     |

### Labels

Labels mark bytecode positions for jump targets. They appear on their own line with a trailing colon:

```
L0:
  iter_next 1 L28
  ; loop body
  jump L0
L28:
  halt
```

Label names must begin with `L` followed by digits. In decompiler output, the number corresponds to the bytecode offset,
but the assembler resolves labels by name regardless of the number used.

### Instruction Categories

**Pool-indexed** (`@N` operand): `const_int`, `const_uint`, `const_float`, `const_str`, `load_global`, `store_global`,
`get_field`, `set_field`, `assert`

**Slot-indexed** (numeric operand): `load_local`, `store_local`, `iter_init`

**Jump targets** (label operand): `jump`, `jump_false`, `jump_true`

**Multi-operand**:

`call` and `tail_call` take a **constant pool index** that names the target. The VM resolves the name at dispatch
time against user-defined functions first, then built-ins. The assembler currently accepts these indices as plain
numbers only (not `@N`); the decompiler emits them the same way and annotates each call with a trailing `;` comment
that names the function.

| Instruction  | Operands                          | Example                                                        |
|--------------|-----------------------------------|----------------------------------------------------------------|
| `call`       | `name_pool_idx argc`              | `call 7 1` (pool entry 7 names the function to invoke)         |
| `tail_call`  | `name_pool_idx argc`              | `tail_call 7 1` (same resolution as `call`)                    |
| `closure`    | `func_idx capture_count`          | `closure 3 2` (create closure over function 3 with 2 captures) |
| `call_value` | `argc`                            | `call_value 1` (call function value with 1 arg)                |
| `new_object` | `type_idx field_count`            | `new_object 0 3`                                               |
| `new_list`   | `count`                           | `new_list 5`                                                   |
| `new_map`    | `count`                           | `new_map 3`                                                    |
| `new_tuple`  | `count`                           | `new_tuple 2`                                                  |
| `new_set`    | `count`                           | `new_set 3`                                                    |
| `new_enum`   | `enum_idx variant_idx data_count` | `new_enum 0 1 2`                                               |
| `match_enum` | `variant_idx label`               | `match_enum 0 L30`                                             |
| `enum_data`  | `field_idx`                       | `enum_data 0`                                                  |
| `iter_next`  | `var_slot label`                  | `iter_next 1 L50`                                              |

**No operands**: `push_true`, `push_false`, `push_void`, `pop`, `dup`, `nop`, `add`, `sub`, `mul`, `div`, `mod`, `neg`,
`cmp_eq`, `cmp_ne`, `cmp_lt`, `cmp_le`, `cmp_gt`, `cmp_ge`, `not`, `return`, `halt`, `get_index`, `set_index`,
`assert_expr`, `in_check`, `new_range`, `new_range_step`, `bit_and`, `bit_or`, `bit_xor`, `bit_not`, `bit_shl`,
`bit_shr`

---

## Binary File Format (.safeb)

The `.safeb` format is a self-contained binary that encodes the complete program. All multi-byte values are big-endian.

### Header (36 bytes)

| Offset | Size | Field          | Description                                                      |
|--------|------|----------------|------------------------------------------------------------------|
| 0      | 4    | Magic          | `0x53 0x41 0x46 0x45` ("SAFE")                                   |
| 4      | 2    | Version        | Format version (currently `2`)                                   |
| 6      | 2    | Main locals    | Number of local variable slots for main (0 = legacy default 256) |
| 8      | 4    | Constant count | Number of constant pool entries                                  |
| 12     | 4    | Type count     | Number of struct type definitions                                |
| 16     | 4    | Enum count     | Number of enum type definitions                                  |
| 20     | 4    | Function count | Number of function definitions                                   |
| 24     | 4    | Global count   | Number of global variable declarations                           |
| 28     | 4    | Main size      | Size of main bytecode in bytes                                   |
| 32     | 4    | Checksum       | CRC32 checksum over the body (everything after the header)       |

### Constant Pool

Each entry begins with a 1-byte tag:

| Tag | Type    | Encoding                    |
|-----|---------|-----------------------------|
| `1` | Integer | 8-byte signed long          |
| `2` | Float   | 8-byte IEEE 754 double      |
| `3` | String  | 2-byte length + UTF-8 bytes |
| `4` | Name    | 2-byte length + UTF-8 bytes |

### Type Definitions

For each type:

| Size | Field                      |
|------|----------------------------|
| 2    | Name index (constant pool) |
| 2    | Field count                |

For each field:

| Size | Field                                                                                        |
|------|----------------------------------------------------------------------------------------------|
| 2    | Field name index (constant pool)                                                             |
| 1    | Type tag (0=int, 1=float, 2=uint, 3=string, 4=bool, 5=list, 6=map, 7=object, 8=enum, 9=void) |

### Enum Definitions

For each enum:

| Size | Field                      |
|------|----------------------------|
| 2    | Name index (constant pool) |
| 2    | Variant count              |

For each variant:

| Size | Field                              |
|------|------------------------------------|
| 2    | Variant name index (constant pool) |
| 1    | Data field count                   |
| 1×N  | Type tag for each data field       |

### Function Table

For each function:

| Size | Field                                |
|------|--------------------------------------|
| 2    | Name index (constant pool)           |
| 2    | Parameter count                      |
| 2    | Local slot count                     |
| 4    | Bytecode length                      |
| N    | Bytecode bytes                       |
| 1    | Has requires contract (0 or 1)       |
| 4+N  | (if has requires) Length + bytecode  |
| 1    | Has ensures contract (0 or 1)        |
| 4+N  | (if has ensures) Length + bytecode   |
| 1    | Has decreases clause (0 or 1)        |
| 4+N  | (if has decreases) Length + bytecode |

### Global Variables

For each global:

| Size | Field                        |
|------|------------------------------|
| 2    | Name index (constant pool)   |
| 1    | Is constant (0=var, 1=const) |

### Main Bytecode

Raw bytecode bytes (length from header).

---

## Examples

### Minimal: Hello World

Given this SAFE source:

```safe
program hello;
import io;

io:println("Hello, World!");
io:println("SAFE Language v1.0");
```

The bytecode compiler produces this assembly (module wrappers omitted for brevity — see the comprehensive example below
for the full output):

```
.version 2

.constants
  @0 string "Hello, World!"
  @1 string "SAFE Language v1.0"

.main locals=0
  const_str @0  ; "Hello, World!"
  call 1 1      ; io$println
  pop
  const_str @1  ; "SAFE Language v1.0"
  call 1 1      ; io$println
  pop
  halt
.end
```

Execution trace:

1. `const_str @0` — push `"Hello, World!"` onto the stack
2. `call 1 1` — resolve constant-pool entry 1 (the `name` `"io$println"` in a full constant pool) and call it with 1
   argument; prints the string
3. `pop` — discard the return value (void)
4. `const_str @1` — push `"SAFE Language v1.0"`
5. `call 1 1` — same call again
6. `pop` — discard return value
7. `halt` — end execution

---

### Comprehensive: Structs, Enums, Contracts, and Loops

This example demonstrates most assembly features: type and enum definitions, globals, pattern matching, function
contracts, iteration, and object construction.

```safe
program example;
import io;
import std;

type Point {
    int x;
    int y;
}

enum Shape {
    Circle(float),
    Rect(int, int)
}

float area(Shape s)
    ensures result >= 0.0
{
    return case s of {
        Circle(r): r * r * 3.14;
        Rect(w, h): std:str(w * h);
    };
}

int sum(list<int> items) {
    int total = 0;
    for x in items {
        total = total + x;
    }
    return total;
}

Point p = Point { x: 3, y: 4 };
io:println(p.x);

Shape c = Circle(2.5);
io:println(area(c));

const int result = sum([10, 20, 30]);
io:println(result);
```

Running `safe bytecode example.safe` followed by `safe disassemble example.safeb` produces the full assembly below.
Module wrapper functions (e.g. `io$println`) are generated automatically for each imported symbol — they delegate to the
corresponding built-in executor. Only the interesting parts are shown inline with annotations; the full output follows.

#### Constant pool

Every literal value, identifier name, and field name used in the program is stored in the constant pool and referenced
by index (`@N`):

```
.constants
  @0 name "io$print"          ; module-mangled function names
  @1 name "io$println"
  ...
  @13 name "Point"            ; struct type name
  @14 name "x"                ; field name
  @15 name "y"
  @16 name "Shape"            ; enum type name
  @17 name "Circle"           ; variant names
  @18 name "Rect"
  @19 name "area"             ; user function names
  @20 name "sum"
  @21 float 3.14              ; literal values
  @22 float 0.0
  @23 int 0
  @24 string "x"              ; field name strings for construction
  @25 int 3
  @26 string "y"
  @27 int 4
  @28 name "p"                ; global variable names
  @29 float 2.5
  @30 name "c"
  @31 int 10
  @32 int 20
  @33 int 30
  @34 name "result"
```

#### Type and enum declarations

These sections describe the structure of user-defined types so the VM can construct and inspect them:

```
.types
  type Point { x: int, y: int }

.enums
  enum Shape { Circle(float), Rect(int, int) }
```

#### Globals

Top-level variables are declared in the `.globals` section. `var` is mutable, `const` is immutable:

```
.globals
  var p
  var c
  const result
```

#### Function: `area` (pattern matching + contract)

The `area` function demonstrates enum pattern matching compiled to `match_enum`/`enum_data` sequences, and an `ensures`
contract:

```
.function area params=1 locals=5
  load_local 0               ; push s (the Shape argument)
  dup                        ; duplicate for match_enum (it peeks, not pops)
  match_enum 0 L29           ; is it variant 0 (Circle)? if not, jump to L29
  enum_data 0                ; extract field 0 (r) from Circle
  store_local 1              ; r → local 1
  pop                        ; discard the enum value
  load_local 1               ; push r
  load_local 1               ; push r
  mul                        ; r * r
  const_float @21            ; push 3.14
  mul                        ; r * r * 3.14
  jump L62                   ; skip to end of case
L29:
  dup                        ; still have s on stack
  match_enum 1 L60           ; is it variant 1 (Rect)? if not, jump to L60
  enum_data 0                ; extract field 0 (w)
  store_local 2              ; w → local 2
  enum_data 1                ; extract field 1 (h)
  store_local 3              ; h → local 3
  pop                        ; discard the enum value
  load_local 2               ; push w
  load_local 3               ; push h
  mul                        ; w * h
  call 3 1                   ; std$str(w * h)
  jump L62                   ; skip to end of case
L60:
  pop                        ; no match (unreachable here)
  push_void
L62:
  return
  push_void
  return
  .ensures                   ; post-condition: result >= 0.0
    load_local 4             ; the return value is in the slot after params+locals
    const_float @22          ; push 0.0
    cmp_ge                   ; result >= 0.0 → boolean
.end
```

The `.ensures` block runs after the function returns. The VM places the return value in a local slot so the contract can
reference it. If the condition evaluates to `false`, execution halts with a contract violation.

#### Function: `sum` (for-in loop)

The `sum` function demonstrates how `for x in items` compiles to `iter_init` / `iter_next` / `jump`:

```
.function sum params=1 locals=4
  const_int @23              ; push 0
  store_local 1              ; total = 0
  load_local 0               ; push items (the list)
  iter_init 2                ; store iterator state at slot 2
L12:
  iter_next 3 L30            ; advance iterator → local 3 (x), or jump to L30 if done
  load_local 1               ; push total
  load_local 3               ; push x
  add                        ; total + x
  store_local 1              ; total = total + x
  jump L12                   ; loop back
L30:
  load_local 1               ; push total
  return
  push_void
  return
.end
```

#### Main section

The main section contains the top-level program statements. Note how struct construction uses `new_object`, enum
construction uses `new_enum`, and list literals use `new_list`:

```
.main locals=1
  ; Point p = Point { x: 3, y: 4 };
  const_str @24              ; push "x"    (field name)
  const_int @25              ; push 3      (field value)
  const_str @26              ; push "y"
  const_int @27              ; push 4
  new_object 0 2             ; construct Point (type 0) with 2 fields

  store_global @28           ; p = <Point>

  ; io:println(p.x);
  load_global @28            ; push p
  get_field @14              ; p.x (name index 14 = "x")
  call 1 1                   ; io$println(p.x)
  pop

  ; Shape c = Circle(2.5);
  const_float @29            ; push 2.5
  new_enum 0 0 1             ; construct Shape (enum 0), variant 0 (Circle), 1 data field

  store_global @30           ; c = Circle(2.5)

  ; io:println(area(c));
  load_global @30            ; push c
  call 13 1                  ; area(c) — pool entry 13 names "area"
  call 1 1                   ; io$println(result)
  pop

  ; const int result = sum([10, 20, 30]);
  const_int @31              ; push 10
  const_int @32              ; push 20
  const_int @33              ; push 30
  new_list 3                 ; create list [10, 20, 30] from 3 stack values

  call 14 1                  ; sum([10, 20, 30]) — pool entry 14 names "sum"
  store_global @34           ; result = 60

  ; io:println(result);
  load_global @34            ; push result
  call 1 1                   ; io$println(60)
  pop

  halt                       ; end of program
.end
```

Output when run with `safe vm example.safeb`:

```
3
19.625
60
```

#### Full unedited output

For reference, here is the complete decompiler output including all module wrapper functions. These wrappers are
generated for every symbol imported from `io` and `std`:

<details>
<summary>Click to expand full assembly listing</summary>

```
; SAFE Bytecode Assembly
; Decompiled output

.version 2

.constants
  @0 name "io$print"
  @1 name "io$println"
  @2 name "io$input"
  @3 name "std$str"
  @4 name "std$len"
  @5 name "std$range"
  @6 name "std$span"
  @7 name "std$typeof"
  @8 name "std$time"
  @9 name "std$args"
  @10 name "std$exit"
  @11 name "std$integer"
  @12 name "std$decimal"
  @13 name "Point"
  @14 name "x"
  @15 name "y"
  @16 name "Shape"
  @17 name "Circle"
  @18 name "Rect"
  @19 name "area"
  @20 name "sum"
  @21 float 3.14
  @22 float 0.0
  @23 int 0
  @24 string "x"
  @25 int 3
  @26 string "y"
  @27 int 4
  @28 name "p"
  @29 float 2.5
  @30 name "c"
  @31 int 10
  @32 int 20
  @33 int 30
  @34 name "result"

.types
  type Point { x: int, y: int }

.enums
  enum Shape { Circle(float), Rect(int, int) }

.globals
  var p
  var c
  const result

.functions

.function io$print params=1 locals=1
  load_local 0
  call 0 1  ; print
  pop
  push_void
  return
.end

.function io$println params=1 locals=1
  load_local 0
  call 1 1  ; println
  pop
  push_void
  return
.end

.function io$input params=1 locals=1
  load_local 0
  call 2 1  ; input
  return
  push_void
  return
.end

.function std$str params=1 locals=1
  load_local 0
  call 3 1  ; str
  return
  push_void
  return
.end

.function std$len params=1 locals=1
  load_local 0
  call 4 1  ; len
  return
  push_void
  return
.end

.function std$range params=1 locals=1
  load_local 0
  call 5 1  ; range
  return
  push_void
  return
.end

.function std$span params=2 locals=2
  load_local 0
  load_local 1
  call 5 2  ; range
  return
  push_void
  return
.end

.function std$typeof params=1 locals=1
  load_local 0
  call 7 1  ; typeof
  return
  push_void
  return
.end

.function std$time params=0 locals=0
  call 8 0  ; time
  return
  push_void
  return
.end

.function std$args params=0 locals=0
  call 9 0  ; args
  return
  push_void
  return
.end

.function std$exit params=1 locals=1
  load_local 0
  call 10 1  ; exit
  pop
  push_void
  return
.end

.function std$integer params=1 locals=1
  load_local 0
  call 11 1  ; int
  return
  push_void
  return
.end

.function std$decimal params=1 locals=1
  load_local 0
  call 12 1  ; float
  return
  push_void
  return
.end

.function area params=1 locals=5
  load_local 0
  dup
  match_enum 0 L29
  enum_data 0
  store_local 1
  pop
  load_local 1
  load_local 1
  mul
  const_float @21  ; 3.14
  mul
  jump L62
L29:
  dup
  match_enum 1 L60
  enum_data 0
  store_local 2
  enum_data 1
  store_local 3
  pop
  load_local 2
  load_local 3
  mul
  call 3 1  ; std$str
  jump L62
L60:
  pop
  push_void
L62:
  return
  push_void
  return
  .ensures
    load_local 4
    const_float @22  ; 0.0
    cmp_ge
.end

.function sum params=1 locals=4
  const_int @23  ; 0
  store_local 1
  load_local 0
  iter_init 2
L12:
  iter_next 3 L30
  load_local 1
  load_local 3
  add
  store_local 1
  jump L12
L30:
  load_local 1
  return
  push_void
  return
.end

.main locals=1
  const_str @24  ; "x"
  const_int @25  ; 3
  const_str @26  ; "y"
  const_int @27  ; 4
  new_object 0 2  ; Point
  store_global @28  ; p
  load_global @28  ; p
  get_field @14  ; x
  call 1 1  ; io$println
  pop
  const_float @29  ; 2.5
  new_enum 0 0 1
  store_global @30  ; c
  load_global @30  ; c
  call 13 1  ; area
  call 1 1  ; io$println
  pop
  const_int @31  ; 10
  const_int @32  ; 20
  const_int @33  ; 30
  new_list 3
  call 14 1  ; sum
  store_global @34  ; result
  load_global @34  ; result
  call 1 1  ; io$println
  pop
  halt
.end
```

</details>

---

## Common Patterns

### If-Else Expression

SAFE's `if` is expression-oriented (always produces a value):

```
  <condition>
  jump_false ELSE
  <then branch>
  jump END
ELSE:
  <else branch>
END:
  ; result is on top of stack
```

### For-In Loop

```
  <push iterable>
  iter_init ITER_SLOT
LOOP:
  iter_next VAR_SLOT END
  <loop body>
  jump LOOP
END:
```

### Short-Circuit AND

```
  <left>
  dup
  jump_false END   ; if left is false, skip right
  pop              ; discard left (it was true)
  <right>
END:
```

### Short-Circuit OR

```
  <left>
  dup
  jump_true END    ; if left is true, skip right
  pop              ; discard left (it was false)
  <right>
END:
```

### Case Expression (enum matching)

```
  <subject>
  dup
  match_enum VARIANT_0 NEXT_1
  pop
  <branch 0 body>
  jump END
NEXT_1:
  dup
  match_enum VARIANT_1 NEXT_2
  pop
  <branch 1 body>
  jump END
NEXT_2:
  pop
  <default body>
END:
```

### Case Expression with Guard

```
  <subject>
  dup
  match_enum VARIANT_0 NEXT_1
  <extract bindings>
  <guard condition>
  jump_false GUARD_FAIL
  pop                    ; pop subject
  <branch 0 body>
  jump END
GUARD_FAIL:
  ; subject still on stack, fall through
NEXT_1:
  pop
  <default body>
END:
```

### Wildcard with Guard

```
  <subject>
  <guard condition>
  jump_false GUARD_FAIL
  pop                    ; pop subject
  <result>
  jump END
GUARD_FAIL:
  ; subject still on stack, try next branch
```

### Tail-Recursive Function

```
.function sum params=2 locals=2
  load_local 0           ; n
  const_int @0           ; 0
  cmp_le
  jump_false RECURSE
  load_local 1           ; acc
  return
RECURSE:
  load_local 0
  const_int @1           ; 1
  sub
  load_local 1
  load_local 0
  add
  tail_call 0 2          ; reuse frame, jump to start
  push_void
  return
.end
```

### Closure Creation

```
  <push captured values>
  closure FUNC_IDX CAPTURE_COUNT
  ; closure value is now on stack
```

### Calling a Closure

```
  <push closure value>
  <push arguments>
  call_value ARGC
```

### Function Call with Contract

```
  <push arguments>
  call FUNC_IDX ARGC
```

At the VM level, the call instruction:

1. Pushes a new call frame
2. Binds arguments to local slots
3. Evaluates the `requires` bytecode (if present); halts on failure
4. Executes the function body
5. On `return`, evaluates the `ensures` bytecode (if present)
6. Pops the call frame and pushes the return value
