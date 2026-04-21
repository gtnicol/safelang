# SAFE Bytecode Opcode Reference

This document describes every instruction in the SAFE bytecode virtual machine. The VM is stack-based: most instructions
consume operands from the top of the stack and push their results back. All multi-byte operands are encoded big-endian.

## Notation

| Symbol        | Meaning                                                   |
|---------------|-----------------------------------------------------------|
| `[n:2]`       | 2-byte unsigned short operand embedded in the instruction |
| `[n:1]`       | 1-byte unsigned operand embedded in the instruction       |
| `..., a, b →` | Stack before (top on right)                               |
| `→ c`         | Stack after                                               |
| `@pool`       | Index into the constant pool                              |

"Size" is the total instruction size in bytes (1 byte for the opcode itself plus any inline operands).

---

## Constants and Literals

| Opcode        | Hex    | Size | Operands  | Stack Effect | Description                                                                   |
|---------------|--------|------|-----------|--------------|-------------------------------------------------------------------------------|
| `const_int`   | `0x01` | 3    | `@pool:2` | `→ value`    | Push integer from constant pool                                               |
| `const_float` | `0x02` | 3    | `@pool:2` | `→ value`    | Push float from constant pool                                                 |
| `const_str`   | `0x03` | 3    | `@pool:2` | `→ value`    | Push string from constant pool                                                |
| `push_true`   | `0x04` | 1    | —         | `→ true`     | Push boolean `true`                                                           |
| `push_false`  | `0x05` | 1    | —         | `→ false`    | Push boolean `false`                                                          |
| `push_void`   | `0x06` | 1    | —         | `→ void`     | Push the void/unit value                                                      |
| `const_uint`  | `0x0A` | 3    | `@pool:2` | `→ value`    | Push unsigned integer from constant pool (stored as int, interpreted as uint) |

---

## Stack Manipulation

| Opcode | Hex    | Size | Operands | Stack Effect | Description            |
|--------|--------|------|----------|--------------|------------------------|
| `pop`  | `0x07` | 1    | —        | `a →`        | Discard top of stack   |
| `dup`  | `0x08` | 1    | —        | `a → a, a`   | Duplicate top of stack |
| `nop`  | `0x09` | 1    | —        | —            | No operation           |

---

## Variables

Local variables are addressed by slot index within the current call frame. Global variables are addressed by name
through the constant pool.

| Opcode         | Hex    | Size | Operands  | Stack Effect | Description                       |
|----------------|--------|------|-----------|--------------|-----------------------------------|
| `load_local`   | `0x10` | 3    | `slot:2`  | `→ value`    | Push local variable at slot       |
| `store_local`  | `0x11` | 3    | `slot:2`  | `value →`    | Pop and store into local slot     |
| `load_global`  | `0x12` | 3    | `@name:2` | `→ value`    | Push global variable by name      |
| `store_global` | `0x13` | 3    | `@name:2` | `value →`    | Pop and store into global by name |

---

## Arithmetic

All arithmetic opcodes are polymorphic — they operate on both integers and floats. The type of the result matches the
operands (if one is a float, the result is a float). `add` also performs string concatenation when either operand is a
string.

| Opcode | Hex    | Size | Stack Effect | Description                   |
|--------|--------|------|--------------|-------------------------------|
| `add`  | `0x20` | 1    | `a, b → a+b` | Add (or string concatenation) |
| `sub`  | `0x21` | 1    | `a, b → a-b` | Subtract                      |
| `mul`  | `0x22` | 1    | `a, b → a*b` | Multiply                      |
| `div`  | `0x23` | 1    | `a, b → a/b` | Divide                        |
| `mod`  | `0x24` | 1    | `a, b → a%b` | Modulo                        |
| `neg`  | `0x25` | 1    | `a → -a`     | Negate                        |

---

## Comparison

All comparison opcodes pop two values and push a boolean.

| Opcode   | Hex    | Size | Stack Effect  | Description           |
|----------|--------|------|---------------|-----------------------|
| `cmp_eq` | `0x30` | 1    | `a, b → a==b` | Equal                 |
| `cmp_ne` | `0x31` | 1    | `a, b → a!=b` | Not equal             |
| `cmp_lt` | `0x32` | 1    | `a, b → a<b`  | Less than             |
| `cmp_le` | `0x33` | 1    | `a, b → a<=b` | Less than or equal    |
| `cmp_gt` | `0x34` | 1    | `a, b → a>b`  | Greater than          |
| `cmp_ge` | `0x35` | 1    | `a, b → a>=b` | Greater than or equal |

---

## Logic

| Opcode | Hex    | Size | Stack Effect | Description |
|--------|--------|------|--------------|-------------|
| `not`  | `0x38` | 1    | `a → !a`     | Logical not |

Short-circuit `&&` and `||` are compiled using `dup` + conditional jumps rather than dedicated opcodes.

---

## Bitwise

All bitwise opcodes operate on integer values only.

| Opcode    | Hex    | Size | Stack Effect  | Description              |
|-----------|--------|------|---------------|--------------------------|
| `bit_and` | `0xB7` | 1    | `a, b → a&b`  | Bitwise AND              |
| `bit_or`  | `0xB8` | 1    | `a, b → a\|b` | Bitwise OR               |
| `bit_xor` | `0xB9` | 1    | `a, b → a^b`  | Bitwise XOR              |
| `bit_not` | `0xBA` | 1    | `a → ~a`      | Bitwise NOT (complement) |
| `bit_shl` | `0xBB` | 1    | `a, b → a<<b` | Shift left               |
| `bit_shr` | `0xBC` | 1    | `a, b → a>>b` | Arithmetic shift right   |

---

## Control Flow

Jump offsets are 16-bit signed values relative to the byte immediately following the offset operand. A forward jump of
`+0` continues to the next instruction; a negative offset jumps backward for loops.

| Opcode       | Hex    | Size | Operands   | Stack Effect | Description                        |
|--------------|--------|------|------------|--------------|------------------------------------|
| `jump`       | `0x40` | 3    | `offset:2` | —            | Unconditional jump                 |
| `jump_false` | `0x41` | 3    | `offset:2` | `cond →`     | Pop; jump if false                 |
| `jump_true`  | `0x42` | 3    | `offset:2` | `cond →`     | Pop; jump if true                  |
| `return`     | `0x43` | 1    | —          | `value →`    | Pop return value, return to caller |
| `halt`       | `0x44` | 1    | —          | —            | Stop execution (end of main)       |

---

## Function Calls

### User-Defined Functions

| Opcode      | Hex    | Size | Operands          | Stack Effect               | Description                           |
|-------------|--------|------|-------------------|----------------------------|---------------------------------------|
| `call`      | `0x50` | 4    | `@name:2, argc:1` | `arg₁, ..., argₙ → result` | Call function by pooled name          |
| `tail_call` | `0xBE` | 4    | `@name:2, argc:1` | `arg₁, ..., argₙ → result` | Tail call — reuses current call frame |

Both operands reference the **constant pool**: the 2-byte index names a pool entry (a string or `name` constant) and the
VM resolves it to either a user-defined function or a built-in at dispatch time. User functions take priority over
built-ins of the same name.

The `call` instruction pops `argc` arguments from the stack (first argument deepest), pushes a new call frame, binds
arguments to local slots 0..argc-1, and begins executing the function's bytecode. When the function hits `return`, its
return value is pushed onto the caller's stack.

The `tail_call` instruction is an optimization for self-recursive calls in return position. Instead of pushing a new
call frame, it resets the current frame's locals with the new arguments and jumps back to the start of the function.
This allows unbounded self-recursion without stack overflow. The bytecode compiler emits `tail_call` automatically when
the return expression is a direct self-call.

### Built-in Functions

Built-in functions are called via the standard `call` opcode: since the operand is a pooled name, the VM can route to
either a user-defined function or a built-in executor depending on how the name resolves at dispatch time.

Built-in function IDs (102 total, organized by module):

**io** (IDs 0–1, 41):

| ID | Name      | Args | Description                 |
|----|-----------|------|-----------------------------|
| 0  | `print`   | 1    | Print value (no newline)    |
| 1  | `println` | 1    | Print value with newline    |
| 41 | `input`   | 1    | Read user input with prompt |

**std** (IDs 2–6, 42–45):

| ID | Name     | Args | Description                                                             |
|----|----------|------|-------------------------------------------------------------------------|
| 2  | `len`    | 1    | Length of list or string                                                |
| 3  | `range`  | 1–2  | Generate list `[0, ..., n-1]` (1 arg) or `[start, ..., end-1]` (2 args) |
| 4  | `str`    | 1    | Convert to string                                                       |
| 5  | `int`    | 1    | Convert to integer (`integer` is an alias)                              |
| 6  | `float`  | 1    | Convert to float (`decimal` is an alias)                                |
| 42 | `exit`   | 1    | Exit program with code                                                  |
| 43 | `args`   | 0    | Get command-line arguments as list                                      |
| 44 | `time`   | 0    | Get current time in milliseconds                                        |
| 45 | `typeof` | 1    | Get type name as string                                                 |

**math** (IDs 12–22, 62–71):

| ID | Name      | Args | Description                      |
|----|-----------|------|----------------------------------|
| 12 | `sqrt`    | 1    | Square root                      |
| 13 | `pow`     | 2    | Raise to power                   |
| 14 | `abs`     | 1    | Absolute value                   |
| 15 | `min`     | 2    | Minimum of two values            |
| 16 | `max`     | 2    | Maximum of two values            |
| 17 | `floor`   | 1    | Floor to integer                 |
| 18 | `ceil`    | 1    | Ceiling to integer               |
| 19 | `round`   | 1    | Round to nearest integer         |
| 20 | `log`     | 1    | Natural logarithm                |
| 21 | `sin`     | 1    | Sine                             |
| 22 | `cos`     | 1    | Cosine                           |
| 62 | `tan`     | 1    | Tangent                          |
| 63 | `asin`    | 1    | Arcsine                          |
| 64 | `acos`    | 1    | Arccosine                        |
| 65 | `atan`    | 1    | Arctangent                       |
| 66 | `atan2`   | 2    | Two-argument arctangent          |
| 67 | `exp`     | 1    | Euler's number raised to power   |
| 68 | `log10`   | 1    | Base-10 logarithm                |
| 69 | `rand`    | 0    | Random float in [0, 1)           |
| 70 | `randint` | 2    | Random int in [low, high)        |
| 71 | `seed`    | 1    | Seed the random number generator |

**strings** (IDs 23–34, 72–74):

| ID | Name         | Args | Description                            |
|----|--------------|------|----------------------------------------|
| 23 | `substring`  | 3    | Extract substring (string, start, end) |
| 24 | `indexOf`    | 2    | Find first occurrence of substring     |
| 25 | `charAt`     | 2    | Get character at index                 |
| 26 | `split`      | 2    | Split string by delimiter              |
| 27 | `trim`       | 1    | Remove leading/trailing whitespace     |
| 28 | `upper`      | 1    | Convert to uppercase                   |
| 29 | `lower`      | 1    | Convert to lowercase                   |
| 30 | `replace`    | 3    | Replace first occurrence               |
| 31 | `starts`     | 2    | Check if string starts with prefix     |
| 32 | `ends`       | 2    | Check if string ends with suffix       |
| 33 | `join`       | 2    | Join list of strings with separator    |
| 34 | `chars`      | 1    | Split string into list of characters   |
| 72 | `matches`    | 2    | Test if string matches regex pattern   |
| 73 | `findall`    | 2    | Find all regex matches, return list    |
| 74 | `replaceall` | 3    | Replace all regex matches              |

**collections** (IDs 7–11, 46–49, 58–61):

| ID | Name         | Args | Description                             |
|----|--------------|------|-----------------------------------------|
| 7  | `append`     | 2    | Append element to list, return new list |
| 8  | `keys`       | 1    | Get list of map keys                    |
| 9  | `values`     | 1    | Get list of map values                  |
| 10 | `contains`   | 2    | Check if map/list contains key/element  |
| 11 | `size`       | 1    | Size of map or set                      |
| 46 | `remove`     | 2    | Remove element from collection          |
| 47 | `slice`      | 3    | Extract sublist (list, start, end)      |
| 48 | `reverse`    | 1    | Reverse a list                          |
| 49 | `sort`       | 1    | Sort a list                             |
| 58 | `add`        | 2    | Add element to set                      |
| 59 | `union`      | 2    | Set union                               |
| 60 | `intersect`  | 2    | Set intersection                        |
| 61 | `difference` | 2    | Set difference                          |

**file** (IDs 35–40, 50–57, 75–78):

| ID | Name            | Args | Description                            |
|----|-----------------|------|----------------------------------------|
| 35 | `read`          | 1    | Read file content by path (raw string) |
| 36 | `write`         | 2    | Write file content by path (raw void)  |
| 37 | `appendfile`    | 2    | Append to file by path                 |
| 38 | `exists`        | 1    | Check if file exists                   |
| 39 | `delete`        | 1    | Delete a file                          |
| 40 | `lines`         | 1    | Read file as list of lines by path     |
| 50 | `fileopen`      | 2    | Open file handle (path, mode)          |
| 51 | `fileclose`     | 1    | Close file handle                      |
| 52 | `fileread`      | 1    | Read from file handle → ReadResult     |
| 53 | `filewrite`     | 2    | Write to file handle → WriteResult     |
| 54 | `filereadlines` | 1    | Read lines from handle → LinesResult   |
| 55 | `filevalid`     | 1    | Check if file handle is valid          |
| 56 | `fileload`      | 1    | Read file by path → ReadResult         |
| 57 | `filesave`      | 2    | Write file by path → WriteResult       |
| 75 | `listdir`       | 1    | List directory contents                |
| 76 | `mkdir`         | 1    | Create directory                       |
| 77 | `rmdir`         | 1    | Remove directory                       |
| 78 | `isdir`         | 1    | Check if path is directory             |

**binary** (IDs 79–97):

| ID | Name       | Args | Description                                      |
|----|------------|------|--------------------------------------------------|
| 79 | `balloc`   | 1    | Allocate bytes buffer of given size              |
| 80 | `bget`     | 2    | Get byte at index                                |
| 81 | `bset`     | 3    | Set byte at index, return new bytes              |
| 82 | `bslice`   | 3    | Extract byte slice (bytes, start, end)           |
| 83 | `bconcat`  | 2    | Concatenate two byte buffers                     |
| 84 | `bencode`  | 1    | Encode string to UTF-8 bytes                     |
| 85 | `bdecode`  | 1    | Decode UTF-8 bytes to string                     |
| 86 | `bpack`    | 2    | Pack integer into bytes (value, width)           |
| 87 | `bunpack`  | 3    | Unpack integer from bytes (bytes, offset, width) |
| 88 | `bpatch`   | 3    | Patch bytes at offset with data                  |
| 89 | `bcompare` | 2    | Compare two byte buffers                         |
| 90 | `bhex`     | 1    | Convert bytes to hex string                      |
| 91 | `bopen`    | 2    | Open binary file handle (path, mode)             |
| 92 | `bclose`   | 1    | Close binary file handle                         |
| 93 | `bread`    | 2    | Read N bytes from binary file handle             |
| 94 | `bwrite`   | 2    | Write bytes to binary file handle                |
| 95 | `bseek`    | 2    | Seek to offset in binary file handle             |
| 96 | `bsize`    | 1    | Get file size in bytes                           |
| 97 | `bflush`   | 1    | Flush binary file handle                         |

**hash** (IDs 98–100):

| ID  | Name     | Args | Description               |
|-----|----------|------|---------------------------|
| 98  | `fnv`    | 1    | FNV-1a hash of bytes      |
| 99  | `crc`    | 1    | CRC32 hash of bytes       |
| 100 | `murmur` | 1    | MurmurHash3 hash of bytes |

**env** (ID 101):

| ID  | Name     | Args | Description              |
|-----|----------|------|--------------------------|
| 101 | `getenv` | 1    | Get environment variable |

---

## Closures and Higher-Order Functions

| Opcode       | Hex    | Size | Operands                      | Stack Effect                   | Description                                  |
|--------------|--------|------|-------------------------------|--------------------------------|----------------------------------------------|
| `closure`    | `0xB4` | 4    | `func_idx:2, capture_count:1` | `cap₁, ..., capₙ → closure`    | Create closure capturing N values from stack |
| `call_value` | `0xB5` | 2    | `argc:1`                      | `fn, arg₁, ..., argₙ → result` | Call a function value (closure or reference) |

Lambdas are compiled as anonymous functions (`__lambda_N`). The `closure` instruction captures variables from the
enclosing scope by popping them from the stack and bundling them with a function index. `call_value` pops a function
value and its arguments, injecting captured variables into the new call frame after the parameters.

---

## Objects (Structs)

| Opcode       | Hex    | Size | Operands                    | Stack Effect           | Description               |
|--------------|--------|------|-----------------------------|------------------------|---------------------------|
| `new_object` | `0x60` | 4    | `type_idx:2, field_count:1` | `f₁, ..., fₙ → object` | Construct struct instance |
| `get_field`  | `0x61` | 3    | `@name:2`                   | `object → value`       | Read field by name        |
| `set_field`  | `0x62` | 3    | `@name:2`                   | `object, value → value` | Write field by name; leaves the assigned value on the stack (so assignments can nest as expressions) |

---

## Collections

| Opcode           | Hex    | Size | Operands  | Stack Effect                 | Description                         |
|------------------|--------|------|-----------|------------------------------|-------------------------------------|
| `new_list`       | `0x70` | 3    | `count:2` | `e₁, ..., eₙ → list`         | Create list from N stack values     |
| `new_map`        | `0x71` | 3    | `count:2` | `k₁, v₁, ..., kₙ, vₙ → map`  | Create map from N key-value pairs   |
| `get_index`      | `0x72` | 1    | —         | `collection, index → value`  | Read element by index/key           |
| `set_index`      | `0x73` | 1    | —         | `collection, index, value →` | Write element by index/key          |
| `new_tuple`      | `0xB2` | 3    | `count:2` | `e₁, ..., eₙ → tuple`        | Create tuple from N stack values    |
| `new_set`        | `0xB3` | 3    | `count:2` | `e₁, ..., eₙ → set`          | Create set from N stack values      |
| `new_range`      | `0xB1` | 1    | —         | `start, end → list`          | Create list from range [start..end] |
| `new_range_step` | `0xBD` | 1    | —         | `start, end, step → list`    | Create list from range with step    |

---

## Enums

| Opcode       | Hex    | Size | Operands                                  | Stack Effect               | Description                                            |
|--------------|--------|------|-------------------------------------------|----------------------------|--------------------------------------------------------|
| `new_enum`   | `0x80` | 6    | `enum_idx:2, variant_idx:2, data_count:1` | `d₁, ..., dₙ → enum_value` | Construct enum variant with data fields                |
| `match_enum` | `0x81` | 5    | `variant_idx:2, offset:2`                 | `enum_value → enum_value`  | If variant doesn't match, jump; otherwise fall through |
| `enum_data`  | `0x82` | 2    | `field_idx:1`                             | `enum_value → enum_value, field_value` | Extract data field without consuming the enum (enables chained extraction in case bodies) |

---

## Assertions and Contracts

| Opcode        | Hex    | Size | Operands | Stack Effect  | Description                                                       |
|---------------|--------|------|----------|---------------|-------------------------------------------------------------------|
| `assert`      | `0x90` | 3    | `@msg:2` | `cond →`      | Pop condition; if false, halt with error message from pool        |
| `assert_expr` | `0x91` | 1    | —        | `cond, msg →` | Pop condition and message; if false, halt with the message string |

Functions may also have attached `requires` and `ensures` bytecode chunks. The `requires` contract is evaluated before
the function body with parameters loaded; the `ensures` contract is evaluated after the return value is computed.

---

## Iteration

SAFE uses iterator-based for loops. Iterators are stored in local variable slots alongside a hidden index counter.

| Opcode      | Hex    | Size | Operands                   | Stack Effect          | Description                                                                           |
|-------------|--------|------|----------------------------|-----------------------|---------------------------------------------------------------------------------------|
| `iter_init` | `0xA0` | 3    | `slot:2`                   | `iterable →`          | Pop iterable (list), store iterator state at slot                                     |
| `iter_next` | `0xA1` | 5    | `var_slot:2, end_offset:2` | `→ element` (or jump) | Advance iterator; push next element into var_slot, or jump to end_offset if exhausted |

A typical for loop compiles as:

```
  <push iterable>
  iter_init ITER_SLOT
LOOP_START:
  iter_next VAR_SLOT LOOP_END
  <loop body>
  jump LOOP_START
LOOP_END:
```

---

## Membership

| Opcode     | Hex    | Size | Stack Effect                 | Description                                           |
|------------|--------|------|------------------------------|-------------------------------------------------------|
| `in_check` | `0xB0` | 1    | `element, collection → bool` | Check if element is in collection (list, map, or set) |

---

## Opcode Map

Quick reference of all opcodes sorted by hex value:

```
0x01  const_int         0x30  cmp_eq           0x72  get_index
0x02  const_float       0x31  cmp_ne           0x73  set_index
0x03  const_str         0x32  cmp_lt           0x80  new_enum
0x04  push_true         0x33  cmp_le           0x81  match_enum
0x05  push_false        0x34  cmp_gt           0x82  enum_data
0x06  push_void         0x35  cmp_ge           0x90  assert
0x07  pop               0x38  not              0x91  assert_expr
0x08  dup               0x40  jump             0xA0  iter_init
0x09  nop               0x41  jump_false       0xA1  iter_next
0x0A  const_uint        0x42  jump_true        0xB0  in_check
0x10  load_local        0x43  return           0xB1  new_range
0x11  store_local       0x44  halt             0xB2  new_tuple
0x12  load_global       0x50  call             0xB3  new_set
0x13  store_global                           0xB4  closure
0x20  add               0x60  new_object       0xB5  call_value
0x21  sub               0x61  get_field        0xB7  bit_and
0x22  mul               0x62  set_field        0xB8  bit_or
0x23  div               0x70  new_list         0xB9  bit_xor
0x24  mod               0x71  new_map          0xBA  bit_not
0x25  neg                                      0xBB  bit_shl
                                               0xBC  bit_shr
                                               0xBD  new_range_step
                                               0xBE  tail_call
```
