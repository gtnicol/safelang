# The SAFE Programming Language

**SAFE** (Simple Atomic Finite Expressions) is a programming language designed to be practical for everyday tasks while
being amenable to formal verification and **guaranteed to terminate**. Unlike general-purpose languages, SAFE is not
Turing-complete by design — every program is guaranteed to finish executing. This makes SAFE ideal for configuration
logic, data transformations, verified computations, and anywhere you need confidence that code will always produce a
result.

SAFE comes with a complete toolchain: interpreter, bytecode compiler and VM, C code generator (compiles to native
binaries), WebAssembly compiler, assembler, and decompiler.

---

## Getting Started

### Hello World

```safe
program hello;
import io;

io:println("Hello, World!");
```

Every SAFE program starts with a `program` header and a name. The `import` statement brings in standard library
modules — here we import `io` for console output. Functions from imported modules are called with colon syntax:
`io:println(...)`.

### Running Your Program

> **CLI invocation.** Examples below use a short `safe <subcommand>` form. The actual command is
> `java -jar target/safe-lang-1.0.0-cli.jar <subcommand>` after `mvn clean package -DskipTests`; alias
> `safe` to that in your shell if you want the short form to work literally.

```bash
# Interpret directly
safe run hello.safe

# Compile to bytecode and run in the VM
safe bytecode hello.safe
safe vm hello.safeb

# Compile to a native binary via C
safe build hello.safe
./hello

# Compile to WebAssembly (WASI)
safe wasm hello.safe
wasmtime hello.wasm
```

---

## Variables and Constants

Variables are declared with a type, name, and initial value:

```safe
int age = 30;
float pi = 3.14159;
string name = "Alice";
boolean active = true;
```

Use `const` to declare immutable values:

```safe
const int MAX_SIZE = 100;
const string GREETING = "Hello from SAFE";
```

Constants cannot be reassigned after declaration. Function parameters can also be marked `const`:

```safe
int double(const int x) {
    return x * 2;
}
```

---

## Types

### Primitive Types

| Type      | Description             | Example              |
|-----------|-------------------------|----------------------|
| `int`     | 64-bit signed integer   | `42`, `-7`, `0xFF`   |
| `uint`    | 64-bit unsigned integer | `42u`, `0xFFu`       |
| `float`   | 64-bit floating-point   | `3.14`, `1e10`       |
| `string`  | Text                    | `"hello"`, `'hello'` |
| `boolean` | True or false           | `true`, `false`      |
| `void`    | No value                | used for procedures  |

### Collection Types

```safe
list<int> numbers = [1, 2, 3, 4, 5];
map<string, int> ages = {"alice": 30, "bob": 25};
set<int> unique = #{1, 2, 3};
(int, string) pair = (42, "hello");
```

- **Lists** are ordered, indexed collections: `[1, 2, 3]`
- **Maps** are key-value stores: `{"key": value}`
- **Sets** use hash-brace notation for unique elements: `#{1, 2, 3}`
- **Tuples** are fixed-size, mixed-type groups: `(1, "hi", true)`

### Union Types

A value can be one of several types:

```safe
int|float number = 42;
int|float|string flexible = "hello";
```

Union types are useful for functions that accept or return multiple types:

```safe
int|float abs(int|float x) {
    return if (x >= 0) then x else 0 - x;
}
```

### Ranges

Ranges produce lists of integers:

```safe
list<int> digits = 0..9;
list<int> odds = 1..20 step 2;
```

---

## Strings

SAFE supports several string literal forms:

```safe
"double quoted"
'single quoted'
"""multi-line
   string"""
```

### String Interpolation

Backtick strings support embedded expressions with `${}`:

```safe
string name = "World";
int count = 42;

io:println(`Hello, ${name}!`);
io:println(`The answer is ${count}`);
io:println(`Sum: ${10 + 20}`);
```

### Escape Sequences

Standard escapes work in all string forms: `\n` (newline), `\t` (tab), `\\` (backslash), `\"`, `\'`, `\xHH` (hex),
`\uHHHH` (unicode).

---

## Operators

### Arithmetic

```safe
a + b       // addition (also string concatenation)
a - b       // subtraction
a * b       // multiplication
a / b       // division
a % b       // modulo
```

### Comparison

```safe
a == b      a != b
a < b       a <= b
a > b       a >= b
```

### Logical

```safe
a && b      // and
a || b      // or
!a          // not
```

### Bitwise

```safe
a & b       // AND
a | b       // OR
a ^ b       // XOR
~a          // NOT
a << b      // left shift
a >> b      // right shift
```

### Membership

```safe
3 in [1, 2, 3]          // true
"el" in "hello"          // true
"key" in {"key": 1}      // true
```

---

## Control Flow

### If-Then-Else

SAFE uses expression-based conditionals — there are no block `if` statements. Every `if` produces a value:

```safe
int absolute = if (x >= 0) then x else 0 - x;

string label = if (score > 90) then "A"
    else if (score > 80) then "B"
    else if (score > 70) then "C"
    else "F";
```

### Pattern Matching with Case-Of

`case` expressions match a value against patterns:

```safe
string describe(int day) {
    return case day of {
        0: "Sunday";
        6: "Saturday";
        default: "Weekday";
    };
}
```

Pattern matching shines with enums (see [Enums](#enums)):

```safe
string show(Shape s) {
    return case s of {
        Circle(r):        `Circle with radius ${r}`;
        Rectangle(w, h):  `Rectangle ${w}x${h}`;
        Point:            "A point";
    };
}
```

#### Guard Conditions

Patterns can include guards with `if`:

```safe
case result of {
    Ok(x) if x > 0:  "positive result";
    Ok(x):           "zero or negative";
    Err(msg):        `error: ${msg}`;
}
```

#### Wildcard Patterns

Use `_` or `default` to match anything:

```safe
case x of {
    1: "one";
    2: "two";
    _: "something else";
}
```

### For-In Loops

`for` loops iterate over lists, strings, sets, maps, and ranges:

```safe
for i in 1..10 {
    io:println(i);
}

for name in ["Alice", "Bob", "Carol"] {
    io:println(`Hello, ${name}!`);
}

for char in "SAFE" {
    io:print(char + " ");
}
// S A F E

for key in {"a": 1, "b": 2} {
    io:println(key);
}
```

Loops also work with `std:range(n)` which generates 0 to n-1, and `std:span(a, b)` for arbitrary ranges:

```safe
for i in std:range(5) {
    io:print(i);    // 0 1 2 3 4
}
```

### While Loops with Bounds

`while` loops **require** a `bound` to guarantee termination. The bound is evaluated once and limits how many iterations
can execute:

```safe
int x = 100;
while (x > 0) bound (100) {
    x = x - 1;
}
```

The bound is a hard upper limit — the loop stops when the condition becomes false **or** the bound is reached, whichever
comes first.

### Do Blocks

`do` blocks let you sequence statements and produce a final expression value:

```safe
int result = do {
    int a = 10;
    int b = 20;
    a + b
};
// result is 30
```

The last line in a `do` block is the returned value (no semicolon after it).

---

## Functions

Functions are declared with a return type, name, parameters, and body:

```safe
int add(int a, int b) {
    return a + b;
}

string greet(string name) {
    return `Hello, ${name}!`;
}
```

### Default Parameters

Parameters can have default values. Defaults must come after required parameters:

```safe
int clamp(int x, int lo = 0, int hi = 100) {
    return if (x < lo) then lo else if (x > hi) then hi else x;
}

io:println(clamp(150));         // 100
io:println(clamp(50, 10, 80));  // 50
```

### Contracts

Functions can declare preconditions (`requires`) and postconditions (`ensures`):

```safe
int factorial(int n)
    requires n >= 0
    ensures result >= 1
{
    int r = 1;
    for i in std:range(n) {
        r = r * (i + 1);
    }
    return r;
}
```

Contracts are checked at runtime. A `requires` violation means the caller passed invalid arguments. An `ensures`
violation means the function has a bug.

### Visibility

In modules, functions can be `public` (exported) or `private` (internal):

```safe
module utils;

public int helper(int x) {
    return compute(x) + 1;
}

private int compute(int x) {
    return x * x;
}
```

### Assertions

Use `assert` for runtime invariant checks:

```safe
assert x > 0;
assert items != [], "items must not be empty";
```

---

## Structs

Custom types are declared with `type`:

```safe
type Point {
    int x;
    int y;
}

type Person {
    string name;
    int age;
}
```

Create instances with named fields:

```safe
Point origin = Point { x: 0, y: 0 };
Person alice = Person { name: "Alice", age: 30 };
```

Access and modify fields with dot notation:

```safe
io:println(alice.name);     // Alice
alice.age = 31;
```

### Const Fields

Fields can be immutable:

```safe
type Config {
    const string name;
    int version;
}
```

### Type Aliases

Create shorter names for complex types:

```safe
type IntList = list<int>;
type Comparator = fn(int, int) -> boolean;
```

---

## Enums

Enums define a type with a fixed set of variants:

```safe
enum Color { Red, Green, Blue }
```

Variants can carry associated data:

```safe
enum Shape {
    Circle(float),
    Rectangle(float, float),
    Point
}

Shape s = Circle(3.14);
```

Pattern match on enum values with `case`:

```safe
float area(Shape s) {
    return case s of {
        Circle(r):        r * r * 3.14159;
        Rectangle(w, h):  w * h;
        Point:            0.0;
    };
}
```

### Option and Result

The standard library provides two essential enums for safe error handling:

```safe
import option;
import result;

// Option represents a value that might be absent
Option value = Some(42);
Option missing = None;

int x = option:unwrap(value, 0);     // 42
int y = option:unwrap(missing, 0);   // 0 (default)

// Result represents success or failure
Result ok = Ok(100);
Result err = Err("something went wrong");

int r = result:unwrap(ok, 0);        // 100
boolean bad = result:failed(err);    // true
string msg = result:message(err);    // "something went wrong"
```

---

## Generics

Types and functions can be parameterized with type variables, written with a `?` prefix:

```safe
type Pair {
    ?A first;
    ?B second;
}

Pair p = Pair { first: 42, second: "hello" };
```

Generic functions work with any type:

```safe
?T identity(?T x) {
    return x;
}
```

Collection types use angle-bracket generics:

```safe
list<int> numbers = [1, 2, 3];
map<string, list<int>> groups = {"evens": [2, 4], "odds": [1, 3]};
```

---

## Higher-Order Functions and Lambdas

Functions are first-class values in SAFE. Function types use the `fn` keyword:

```safe
fn(int) -> int transform;
fn(int, int) -> boolean compare;
fn() -> void action;
```

### Lambda Expressions

Lambdas create anonymous functions:

```safe
fn(int) -> int square = fn(x) -> x * x;
fn(int, int) -> int add = fn(a, b) -> a + b;
```

### Functional Programming

The `functional` module provides the classic higher-order functions:

```safe
import functional;

list<int> nums = [1, 2, 3, 4, 5];

// Transform each element
list<int> doubled = functional:map(nums, fn(x) -> x * 2);
// [2, 4, 6, 8, 10]

// Keep elements matching a condition
list<int> evens = functional:filter(nums, fn(x) -> x % 2 == 0);
// [2, 4]

// Reduce to a single value
int total = functional:fold(nums, 0, fn(acc, x) -> acc + x);
// 15

// Execute a side effect for each element
functional:each(nums, fn(x) -> io:println(x));
```

### Closures

Lambdas capture variables from their enclosing scope:

```safe
int factor = 3;
fn(int) -> int multiply = fn(x) -> x * factor;
io:println(multiply(10));    // 30
```

### Functions as Parameters

Pass functions to other functions:

```safe
void apply(list<int> items, fn(int) -> void action) {
    for item in items {
        action(item);
    }
}

apply([1, 2, 3], fn(x) -> io:println(x * x));
// 1
// 4
// 9
```

### Functions Returning Functions

```safe
fn(int) -> int multiplier(int factor) {
    return fn(x) -> x * factor;
}

fn(int) -> int triple = multiplier(3);
io:println(triple(7));    // 21
```

---

## Tuple Destructuring

Tuples can be unpacked into individual variables:

```safe
(int, string) data = (42, "hello");
const (number, text) = data;

io:println(number);     // 42
io:println(text);       // hello
```

---

## Index Access

Lists, strings, tuples, and maps support bracket indexing:

```safe
list<int> items = [10, 20, 30];
int first = items[0];       // 10
items[1] = 99;              // mutation

string word = "SAFE";
string letter = word[0];    // "S"

(int, string) pair = (42, "hi");
int x = pair[0];            // 42

map<string, int> ages = {"alice": 30};
int a = ages["alice"];      // 30
ages["bob"] = 25;           // insert
```

---

## The Module System

### Programs vs Modules

A **program** is an executable entry point. A **module** is a reusable library:

```safe
// greetings.safe
module greetings;

public string hello(string name) {
    return `Hello, ${name}!`;
}

public string farewell(string name) {
    return `Goodbye, ${name}!`;
}
```

```safe
// main.safe
program main;
import io;
import greetings;

io:println(greetings:hello("World"));
io:println(greetings:farewell("World"));
```

### Calling Conventions

- **Colon** for function calls: `math:sqrt(4.0)`
- **Dot** for constants and variables: `math.PI`

### Selective Imports

Import only what you need:

```safe
import math { sqrt, pow, PI };

float x = sqrt(16.0);      // OK
float p = PI;               // OK (via math.PI)
// float s = sin(1.0);      // ERROR: sin not imported
```

---

## Standard Library

### io — Input and Output

```safe
import io;

io:print("no newline");
io:println("with newline");
string answer = io:input("Enter your name: ");
```

### std — Core Utilities

```safe
import std;

std:str(42)             // "42" — convert anything to string
std:len("hello")        // 5 — length of string, list, map, or set
std:range(5)            // [0, 1, 2, 3, 4]
std:span(3, 7)          // [3, 4, 5, 6, 7]
std:typeof(3.14)        // "float"
std:time()              // current time in milliseconds
std:integer("42")       // 42 — parse string to int
std:decimal("3.14")     // 3.14 — parse string to float
```

### math — Mathematics

```safe
import math;

// Basic operations
math:abs(-5)            math:min(3, 7)          math:max(3, 7)
math:floor(3.7)         math:ceil(3.2)          math:round(3.5)
math:clamp(15, 0, 10)   math:sign(-5)

// Powers and roots
math:sqrt(16.0)         math:pow(2.0, 10.0)
math:exp(1.0)           math:log(2.718)         math:log10(100.0)

// Trigonometry
math:sin(x)     math:cos(x)     math:tan(x)
math:asin(x)    math:acos(x)    math:atan(x)    math:atan2(y, x)

// Number theory
math:factorial(10)      math:gcd(48, 18)        math:lcm(4, 6)
math:prime(7)           math:fib(10)

// Aggregation
math:sum([1, 2, 3, 4, 5])

// Random numbers
math:seed(42)           math:rand()             math:randint(1, 100)

// Constants
math.PI                 // 3.14159265358979
math.E                  // 2.71828182845905
```

### strings — String Operations

```safe
import strings;

strings:upper("hello")              // "HELLO"
strings:lower("HELLO")              // "hello"
strings:trim("  hi  ")              // "hi"
strings:split("a,b,c", ",")         // ["a", "b", "c"]
strings:join(["a", "b", "c"], "-")  // "a-b-c"
strings:replace("hello", "l", "r")  // "herro"
strings:substring("hello", 1, 4)    // "ell"
strings:indexOf("hello", "ll")      // 2
strings:charAt("hello", 0)          // "h"
strings:starts("hello", "he")       // true
strings:ends("hello", "lo")         // true
strings:reversed("hello")           // "olleh"
strings:repeat("ha", 3)             // "hahaha"
strings:padleft("42", 5, "0")       // "00042"
strings:padright("hi", 6, ".")      // "hi...."
strings:chars("abc")                // ["a", "b", "c"]
strings:empty("")                   // true
strings:blank("   ")                // true
strings:count("banana", "an")       // 2

// Regex
strings:matches("hello123", "\\d+")           // true
strings:findall("a1b2c3", "\\d")              // ["1", "2", "3"]
strings:replaceall("a1b2", "\\d", "X")        // "aXbX"
```

### collections — Working with Collections

```safe
import collections;

// Lists
collections:size([1, 2, 3])             // 3
collections:count([1, 2, 2, 3], 2)      // 2 (occurrences of target)
collections:append([1, 2], 3)           // [1, 2, 3]
collections:slice([1, 2, 3, 4], 1, 3)   // [2, 3]
collections:reverse([1, 2, 3])          // [3, 2, 1]
collections:sort([3, 1, 2])             // [1, 2, 3]
collections:remove([1, 2, 3], 1)        // [1, 3] (removes at index)
2 in [1, 2, 3]                          // true (use 'in' for list membership)
collections:any([1, 2, 3], 2)           // true
collections:all([2, 2, 2], 2)           // true
collections:unique([1, 1, 2, 2, 3])     // [1, 2, 3]
collections:minimum([3, 1, 2])          // 1
collections:maximum([3, 1, 2])          // 3

// Maps
collections:keys({"a": 1, "b": 2})     // ["a", "b"]
collections:values({"a": 1, "b": 2})   // [1, 2]
collections:contains({"a": 1}, "a")    // true

// Sets
collections:add(#{1, 2}, 3)            // #{1, 2, 3}
collections:union(#{1, 2}, #{2, 3})    // #{1, 2, 3}
collections:intersect(#{1, 2}, #{2, 3})    // #{2}
collections:difference(#{1, 2}, #{2, 3})   // #{1}
```

### file — File Operations

```safe
import file;

// Path-based convenience (one-shot read/write)
file:write("/tmp/data.txt", "Hello!");
ReadResult r = file:read("/tmp/data.txt");

// Handle-based operations — open returns an OpenResult enum (Ok(File) | Err(string));
// unwrap it with case/of before passing the File to load/save/lines/close.
OpenResult handle = file:open("/tmp/data.txt", "r");
int unused = case handle of {
    Ok(f): do {
        ReadResult content = file:load(f);          // read from handle
        file:save(f, "new content");               // write to handle
        LinesResult rows = file:lines(f);          // read lines from handle
        file:close(f);
        0
    };
    Err(msg): do { io:println("open failed: " + msg); 0 };
};

// Path utilities
boolean exists = file:exists("/tmp/data.txt");
file:delete("/tmp/data.txt");

// Directory operations
file:mkdir("/tmp/mydir");
list<string> entries = file:listdir("/tmp");
boolean isdir = file:isdir("/tmp/mydir");
file:rmdir("/tmp/mydir");
```

### sorting — Sort Algorithms

```safe
import sorting;

list<int> nums = [42, 17, 8, 25, 3];
sorting:mergesort(nums, fn(a, b) -> a < b)    // [3, 8, 17, 25, 42]
sorting:quicksort(nums, fn(a, b) -> a < b)    // [3, 8, 17, 25, 42]
sorting:selection(nums, fn(a, b) -> a < b)    // [3, 8, 17, 25, 42]
sorting:insertion(nums, fn(a, b) -> a < b)    // [3, 8, 17, 25, 42]
sorting:timsort(nums, fn(a, b) -> a < b)      // [3, 8, 17, 25, 42]

// Descending order
sorting:mergesort(nums, fn(a, b) -> a > b)    // [42, 25, 17, 8, 3]
```

### stack and queue — Data Structures

```safe
import stack;
import queue;

// Stack (LIFO)
Stack s = stack:create();
s = stack:push(s, 10);
s = stack:push(s, 20);
int top = stack:peek(s);     // 20
s = stack:pop(s);
int depth = stack:depth(s);  // 1

// Queue (FIFO)
Queue q = queue:create();
q = queue:enqueue(q, "first");
q = queue:enqueue(q, "second");
string front = queue:front(q);   // "first"
q = queue:dequeue(q);
```

### tree — Binary Search Tree

```safe
import tree;

Tree t = Empty;
t = tree:insert(t, 5);
t = tree:insert(t, 3);
t = tree:insert(t, 7);

boolean found = tree:find(t, 3);    // true
int smallest = tree:smallest(t);    // 3
int largest = tree:largest(t);      // 7
int size = tree:count(t);           // 3
t = tree:drop(t, 3);               // remove 3
```

### json — JSON Parsing and Serialization

```safe
import json;

// Parse JSON
ParseResult result = json:parse("{\"name\":\"Alice\",\"age\":30}");
Json data = case result of { Ok(j): j; Err(e): Null; };

// Access values
json:get(data, "name")          // Str("Alice")
json:get(data, "age")           // Int(30)
json:at(someArray, 0)           // first element
json:fields(data)               // ["name", "age"]
json:count(data)                // 2
json:kind(data)                 // "object"
json:blank(Null)                // true

// Format to string
json:format(data)               // {"name":"Alice","age":30}

// Build JSON values
Json obj = Object({"key": Str("value"), "n": Int(42)});
Json arr = Array([Int(1), Int(2), Int(3)]);

// JSONL (JSON Lines) — one value per line
list<Json> items = json:jsonl("{\"a\":1}\n{\"b\":2}");

// Load from file
json:load("data.json")                 // parse JSON file
json:load("data.jsonl", JSONL)         // parse JSONL file
```

### xml — XML Parsing and Serialization

```safe
import xml;

// Parse XML
ParseResult result = xml:parse("<root><item id=\"1\">hello</item></root>");
Xml doc = case result of { Ok(v): v; Err(e): Text(""); };

// Navigate the document
xml:tag(doc)                    // "root"
xml:attr(item, "id")            // "1"
xml:text(doc)                   // "hello" (recursive text extraction)
xml:children(doc)               // list of child nodes
xml:find(doc, "item")           // find children by tag name
xml:count(doc)                  // number of children

// Build XML
Xml page = Element("html", {}, [
    Element("body", {}, [
        Element("p", {"class": "intro"}, [Text("Hello!")])
    ])
]);
xml:format(page)                // <html><body><p class="intro">Hello!</p></body></html>

// All DOM node types supported
Comment(" a comment ")          // <!-- a comment -->
CData("raw <data>")            // <![CDATA[raw <data>]]>
PI("xml-stylesheet", "href=\"style.css\"")  // <?xml-stylesheet href="style.css"?>
EntityReference("nbsp")         // &nbsp;

// Load from file
xml:load("document.xml")
```

### csv — CSV Parsing and Formatting

```safe
import csv;

// Parse CSV
list<list<string>> rows = csv:parse("name,age\nAlice,30\nBob,25");

// Parse as records (first row = headers)
list<map<string, string>> records = csv:records("name,age\nAlice,30");
records[0]["name"]              // "Alice"

// Format CSV
csv:format([["a", "b"], ["1", "2"]])    // "a,b\n1,2"
csv:line(["has,comma", "plain"])        // "\"has,comma\",plain"

// Load from file
csv:load("data.csv")           // list of rows
csv:open("data.csv")           // list of records (maps)
```

### path — File Path Manipulation

```safe
import path;

path:join("/usr", "local/bin")  // "/usr/local/bin"
path:parent("/a/b/c")           // "/a/b"
path:name("/a/b/file.txt")      // "file.txt"
path:stem("file.txt")           // "file"
path:extension("file.txt")      // ".txt"
path:absolute("/a")             // true
path:normalize("/a//b/")        // "/a/b"
path:segments("/a/b/c")         // ["a", "b", "c"]
```

### base64 — Base64 Encoding

```safe
import base64;

base64:encode("Hello")          // "SGVsbG8="
base64:decode("SGVsbG8=")       // "Hello"
```

### uuid — UUID Generation

```safe
import uuid;

string id = uuid:generate();    // "550e8400-e29b-41d4-a716-446655440000"
uuid:valid(id)                  // true
uuid:nil()                      // "00000000-0000-0000-0000-000000000000"
```

### env — Environment Variables

```safe
import env;

env:get("HOME")                  // "/Users/alice"
env:has("PATH")                  // true
env:require("PORT", "8080")      // value or fallback
```

### datetime — Date and Time

```safe
import datetime;

Timestamp now = datetime:now();
Timestamp t = datetime:create(2024, 3, 15, 10, 30, 0);

datetime:year(t)                // 2024
datetime:month(t)               // 3
datetime:day(t)                 // 15
datetime:format(t)              // "2024-03-15T10:30:00Z"
datetime:date(t)                // "2024-03-15"

datetime:add(t, 10)             // add 10 days
datetime:diff(a, b)             // difference in days
datetime:leap(2024)             // true
datetime:daysinmonth(2024, 2)   // 29
```

### More modules

Additional stdlib modules not broken out above:

- `functional` — higher-order helpers on lists: `map`, `filter`, `fold`, `flatmap`, `each`, `converge`.
- `option`, `result` — `Some`/`None` and `Ok`/`Err` with `unwrap`, `present`, `failed`, `message`. Patterns
  demonstrated in the Option and Result section earlier.
- `binary` — `bytes` type, packing/slicing/hex, and binary file I/O (`open`, `read`, `write`, `seek`).
- `hash` — FNV-1a, CRC32, MurmurHash3 on bytes; `hash:text(...)` for strings.
- `page`, `btree`, `lsm`, `dbm` — on-disk key-value stores built on a shared 4 KB page abstraction. `btree` is a
  sorted B+ tree, `lsm` a simplified LSM tree (memtable + SSTables), `dbm` a hash-bucketed store.
- `test` — the assertion harness used by `tests/test_*.safe` under `java -jar … test tests/`.

---

## Termination Guarantees

SAFE's defining characteristic is that **every program terminates**. This is enforced through:

1. **Bounded loops**: `for` iterates over finite collections. `while` requires an explicit `bound`.
2. **Structural recursion**: Recursive functions must operate on structurally smaller data (e.g., traversing an enum
   tree).
3. **Arithmetic decrease**: Self-recursive calls must decrease a numeric parameter by a positive constant.
4. **No unbounded recursion**: The termination checker verifies all recursive functions, including mutual recursion via
   strongly connected component analysis.

```safe
// OK: structural recursion on a tree
int size(Tree t) {
    return case t of {
        Empty: 0;
        Node(v, left, right): 1 + size(left) + size(right);
    };
}

// OK: arithmetic decrease
int countdown(int n) {
    return if (n <= 0) then 0 else 1 + countdown(n - 1);
}

// ERROR: no decrease — rejected by termination checker
// int loop(int n) { return loop(n); }
```

---

## A Complete Example

Here is a small but realistic program that demonstrates many SAFE features together:

```safe
program demo;
import io;
import std;
import math;
import strings;
import collections;
import functional;

// A struct for 2D points
type Point {
    float x;
    float y;
}

// Compute distance between two points
float distance(Point a, Point b) {
    float dx = a.x - b.x;
    float dy = a.y - b.y;
    return math:sqrt(dx * dx + dy * dy);
}

// Classify a distance
string classify(float d) {
    return if (d < 1.0) then "near"
        else if (d < 5.0) then "medium"
        else "far";
}

// Create some points
list<Point> points = [
    Point { x: 0.0, y: 0.0 },
    Point { x: 3.0, y: 4.0 },
    Point { x: 1.0, y: 1.0 },
    Point { x: 10.0, y: 10.0 }
];

Point origin = Point { x: 0.0, y: 0.0 };

// Compute distances from origin
for p in points {
    float d = distance(origin, p);
    string label = classify(d);
    io:println(`(${p.x}, ${p.y}) -> distance ${d} (${label})`);
}

// Use functional programming to find nearby points
list<float> distances = functional:map(
    [3.0, 4.0, 1.0, 14.14],
    fn(d) -> math:round(d)
);

int total = functional:fold(
    [1, 2, 3, 4, 5],
    0,
    fn(acc, x) -> acc + x
);

io:println(`Sum of 1..5 = ${total}`);
```

---

## File Extensions

| Extension | Description                        |
|-----------|------------------------------------|
| `.safe`   | SAFE source code                   |
| `.safeb`  | Compiled bytecode binary           |
| `.safea`  | Bytecode assembly (human-readable) |

---

## Toolchain Commands

```bash
safe run file.safe              # Interpret source directly
safe build file.safe            # Compile to native binary via C
safe compile file.safe          # Emit C source code
safe wasm file.safe             # Compile to WebAssembly (.wasm, WASI)
safe bytecode file.safe         # Compile to bytecode
safe vm file.safeb              # Execute bytecode in VM
safe assemble file.safea        # Assemble text to bytecode binary
safe disassemble file.safeb     # Decompile bytecode to assembly text
safe test tests/                # Run SAFE test files
safe test --bytecode tests/     # Run tests via bytecode VM
safe test --native tests/       # Run tests via C backend
safe test --wasm tests/         # Run tests via WebAssembly backend
safe tokens file.safe           # Show lexer tokens (debug)
safe ast file.safe              # Show AST (debug)
```

---

## What SAFE is Not

SAFE intentionally omits several features common in general-purpose languages:

- **No infinite loops** — all iteration is bounded
- **No unrestricted recursion** — recursive functions must demonstrably terminate
- **No null** — use `Option` (Some/None) instead
- **No exceptions** — use `Result` (Ok/Err) for error handling
- **No classes or inheritance** — structs and enums with pattern matching
- **No mutable global state** — module constants are immutable

These omissions are deliberate: they make programs easier to reason about, verify, and trust.
