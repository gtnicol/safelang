grammar SAFEGrammar;

@header {
    package io.safelang.parser.generated;
}

// ============================================================================
// SAFE — Simple Atomic Finite Expressions
// ============================================================================
//
// Design goals:
//   1. Usable for common programming tasks
//   2. Amenable to formal verification (requires/ensures contracts, assertions)
//   3. Guaranteed to terminate (not Turing-complete but still usable)
//   4. Easily compiled to optimized native code
//
// 'program' declares an executable unit.
// 'module' declares a library (imported by programs or other modules).
//
// ============================================================================

// ---------------------------------------------------------------------------
// Top-level structure
// ---------------------------------------------------------------------------

program
    : header
      (imports+=importStatement)*
      (declarations+=declaration)*
      (statements+=statement)*
      EOF
    ;

header
    : PROGRAM IDENTIFIER ';'                           #programDeclaration
    | MODULE  IDENTIFIER ';'                           #moduleDeclarationHeader
    ;

importStatement
    : 'import' IDENTIFIER ('{' IDENTIFIER (',' IDENTIFIER)* '}')? ';'
    ;

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------
// Primitive types, user-defined names, and parameterized (generic) types.
//
// Examples:
//   int                     — primitive
//   string                  — primitive
//   Point                   — user-defined struct
//   list<int>               — parameterized collection
//   map<string, float>      — parameterized collection
//   Result<int, string>     — parameterized enum
//   Optional<Point>         — parameterized enum
//   module:TypeName         — qualified reference to imported type
// ---------------------------------------------------------------------------

type
    : singleType ('|' singleType)*
    ;

singleType
    : baseType ('<' type (',' type)* '>')?
    | '(' type ',' type (',' type)* ')'    // tuple type: (int, string), (int, string, float)
    | FN '(' (type (',' type)*)? ')' ARROW type   // function type: fn(int, string) -> float
    ;

baseType
    : 'int'
    | 'float'
    | 'uint'
    | 'string'
    | 'bytes'
    | 'boolean'
    | 'void'
    | 'set'
    | '?' IDENTIFIER
    | qualifiedName
    ;

// ---------------------------------------------------------------------------
// Declarations (top-level or inside modules)
// ---------------------------------------------------------------------------

declaration
    : typeDeclaration
    | functionDeclaration
    | enumDeclaration
    | typeAlias
    | constDeclaration
    ;

constDeclaration
    : CONST type id=IDENTIFIER '=' expression ';'
    ;

// --- Type alias --------------------------------------------------------------
// Introduces a new name for an existing type.
//
// Examples:
//   type Integer = int;
//   type IntList = list<int>;
//   public type Text = string;
// ---------------------------------------------------------------------------

typeAlias
    : visibility? TYPE IDENTIFIER '=' type ';'
    ;

// --- Struct / record types -------------------------------------------------
// Supports optional visibility and optional generic type parameters.
//
// Examples:
//   type Point { float x; float y; }
//   public type Pair<T, U> { T first; U second; }
// ---------------------------------------------------------------------------

typeDeclaration
    : visibility? TYPE IDENTIFIER typeParameters? '{' fieldDeclaration* '}'
    ;

typeParameters
    : '<' IDENTIFIER (',' IDENTIFIER)* '>'
    ;

fieldDeclaration
    : visibility? CONST? type id=IDENTIFIER ';'
    ;

// --- Functions -------------------------------------------------------------
// Supports optional visibility, optional requires/ensures contracts.
//
// Examples:
//   int add(int a, int b) { return a + b; }
//   public int factorial(int n)
//       requires n >= 0
//       ensures result >= 1
//   {
//       ...
//   }
// ---------------------------------------------------------------------------

functionDeclaration
    : visibility? type id=IDENTIFIER '(' parameters? ')'
      requiresClause?
      ensuresClause?
      decreasesClause?
      body
    ;

requiresClause
    : REQUIRES expression
    ;

ensuresClause
    : ENSURES expression
    ;

decreasesClause
    : DECREASES '(' expression ')'
    ;

parameters
    : parameter (',' parameter)*
    ;

parameter
    : CONST? type id=IDENTIFIER ('=' expression)?
    ;

body
    : '{' bodyStatement* '}'
    ;

// --- Enums -----------------------------------------------------------------
// Tagged unions with optional associated data and optional type parameters.
//
// Examples:
//   enum Color { Red, Green, Blue }
//   public enum Result<T, E> { Ok(T), Err(E) }
//   enum Optional<T> { Some(T), None }
//   enum Shape {
//       Circle(float),
//       Rectangle(float, float),
//       Point
//   }
// ---------------------------------------------------------------------------

enumDeclaration
    : visibility? ENUM IDENTIFIER typeParameters? '{' enumVariant (',' enumVariant)* '}'
    ;

enumVariant
    : IDENTIFIER ( '(' type (',' type)* ')' )?
    ;

// --- Visibility modifiers --------------------------------------------------

visibility
    : PUBLIC
    | PRIVATE
    ;

// ---------------------------------------------------------------------------
// Statements
// ---------------------------------------------------------------------------

statement
    : variableDeclaration
    | destructureDeclaration
    | assignmentStatement
    | indexAssignmentStatement
    | forStatement
    | whileStatement
    | assertStatement
    | expressionStatement
    ;

bodyStatement
    : returnStatement
    | statement
    ;

// --- Variable declaration --------------------------------------------------
// Supports optional const qualifier and optional initializer.
//
// Examples:
//   int x = 5;
//   const string name = "SAFE";
//   list<int> items = [1, 2, 3];
//   const map<string, int> ages = {"alice": 30, "bob": 25};
// ---------------------------------------------------------------------------

variableDeclaration
    : CONST? type id=IDENTIFIER ('=' expression)? ';'
    ;

// --- Tuple destructuring --------------------------------------------------
// Supports destructuring tuples into multiple variables.
//
// Examples:
//   (int, string) (a, b) = pair;
//   const (x, y) = getPoint();
// ---------------------------------------------------------------------------

destructureDeclaration
    : CONST? type? '(' IDENTIFIER (',' IDENTIFIER)+ ')' '=' expression ';'
    ;

// --- Assignment ------------------------------------------------------------
// Assign to a variable or dotted field path.
//
// Examples:
//   x = 10;
//   point.x = 3.14;
// ---------------------------------------------------------------------------

assignmentStatement
    : qualifiedName '=' expression ';'
    ;

// --- Index assignment ------------------------------------------------------
// Assign to an indexed position in a list or map.
//
// Examples:
//   items[0] = 42;
//   ages["charlie"] = 35;
//   matrix[i][j] = 0;
// ---------------------------------------------------------------------------

indexAssignmentStatement
    : qualifiedName indexAccess+ '=' expression ';'
    ;

indexAccess
    : '[' expression ']'
    ;

// --- For-in loop -----------------------------------------------------------
// Iterate over a collection (list, map keys, range).
//
// Examples:
//   for x in range(10) { println(x); }
//   for item in items { total = total + item; }
//   for key in keys(myMap) { println(key); }
// ---------------------------------------------------------------------------

forStatement
    : FOR IDENTIFIER IN expression block
    ;

block
    : '{' statement* '}'
    ;

// --- While statement ---------------------------------------------------------
// Bounded while loop. Iterates while condition is true, up to bound iterations.
//
// Examples:
//   while (x > 0) bound (100) { x = x - 1; }
//   while (!done) bound (1000) { done = check(); }
// ---------------------------------------------------------------------------

whileStatement
    : WHILE '(' condition=expression ')' BOUND '(' bound=expression ')' block
    ;

// --- Assert statement ------------------------------------------------------
// Runtime assertion with optional error message.
//
// Examples:
//   assert x > 0;
//   assert len(items) > 0, "items must not be empty";
// ---------------------------------------------------------------------------

assertStatement
    : ASSERT expression (',' expression)? ';'
    ;

// --- Return statement ------------------------------------------------------

returnStatement
    : RETURN expression? ';'
    ;

// --- Expression statement --------------------------------------------------

expressionStatement
    : expression ';'
    ;

// ---------------------------------------------------------------------------
// Expressions
// ---------------------------------------------------------------------------
// Precedence (lowest to highest):
//   if/case (conditional)
//   ||      (logical or)
//   &&      (logical and)
//   == !=   (equality)
//   < <= > >= in  (relational)
//   + -     (additive)
//   * / %   (multiplicative)
//   ! -     (unary)
//   x[i] x.f f()  (postfix: index, field access, call)
//   literals, grouping
// ---------------------------------------------------------------------------

expression
    : orExpression
    | ifExpression
    | caseExpression
    | doExpression
    | lambdaExpression
    ;

// --- Do expression (multi-statement block) -----------------------------------
// Executes statements in order, returns the value of the final expression.
//
// Examples:
//   do { int x = 5; x + 1 }
//   do { println("hello"); 42 }
// ---------------------------------------------------------------------------

doExpression
    : DO '{' statement* expression '}'
    ;

// --- Lambda expression ----------------------------------------------------
// Anonymous function with optional type annotations on parameters.
//
// Examples:
//   fn(x, y) -> x + y
//   fn(int x, int y) -> x + y
//   fn() -> 42
// ---------------------------------------------------------------------------

lambdaExpression
    : FN '(' lambdaParams? ')' ARROW expression
    ;

lambdaParams
    : lambdaParam (',' lambdaParam)*
    ;

lambdaParam
    : type IDENTIFIER    // typed parameter
    | IDENTIFIER         // untyped parameter (inferred)
    ;

// --- If expression (ternary-style) ----------------------------------------
//
// Examples:
//   if (x > 0) then x else -x
//   if (done) then "yes" else "no"
// ---------------------------------------------------------------------------

ifExpression
    : IF '(' condition=orExpression ')' THEN then=orExpression
      (ELSE else=expression)?
    ;

// --- Case expression (pattern matching) ------------------------------------
// Supports literal patterns, enum variant patterns, and wildcard.
//
// Examples:
//   case color of {
//       Red:   "#ff0000";
//       Green: "#00ff00";
//       Blue:  "#0000ff";
//   }
//
//   case result of {
//       Ok(value):  println(value);
//       Err(msg):   println(msg);
//   }
//
//   case x of {
//       0:       "zero";
//       1:       "one";
//       _:       "other";
//   }
// ---------------------------------------------------------------------------

caseExpression
    : CASE expression OF '{' caseBranch+ defaultBranch? '}'
    ;

caseBranch
    : pattern (IF guard=orExpression)? ':' expression ';'
    ;

defaultBranch
    : DEFAULT ':' expression ';'
    ;

// --- Patterns (used in case branches) --------------------------------------

pattern
    : literal                                                #literalPattern
    | IDENTIFIER '(' IDENTIFIER (',' IDENTIFIER)* ')'       #enumDestructurePattern
    | IDENTIFIER                                             #enumUnitPattern
    | '_'                                                    #wildcardPattern
    ;

// --- Logical OR ------------------------------------------------------------

orExpression
    : left=andExpression ('||' right+=andExpression)*
    ;

// --- Logical AND -----------------------------------------------------------

andExpression
    : left=bitwiseOrExpression ('&&' right+=bitwiseOrExpression)*
    ;

// --- Bitwise OR / XOR / AND ------------------------------------------------

bitwiseOrExpression
    : left=bitwiseXorExpression ('|' right+=bitwiseXorExpression)*
    ;

bitwiseXorExpression
    : left=bitwiseAndExpression ('^' right+=bitwiseAndExpression)*
    ;

bitwiseAndExpression
    : left=valueExpression ('&' right+=valueExpression)*
    ;

// --- Relational / equality -------------------------------------------------

valueExpression
    : arithmeticExpression
    | rangeExpression
    | shiftExpression
    | comparisonExpression
    | equalityExpression
    | inExpression
    ;

rangeExpression
    : left=arithmeticExpression '..' right=arithmeticExpression (STEP step=arithmeticExpression)?
    ;

shiftExpression
    : left=arithmeticExpression '<' '<' right=arithmeticExpression     #shiftLeft
    | left=arithmeticExpression '>' '>' right=arithmeticExpression     #shiftRight
    ;

comparisonExpression
    : left=arithmeticExpression op=('<'|'<='|'>'|'>=') right=arithmeticExpression
    ;

equalityExpression
    : left=arithmeticExpression op=('=='|'!=') right=arithmeticExpression
    ;

inExpression
    : left=arithmeticExpression IN right=arithmeticExpression
    ;

// --- Arithmetic ------------------------------------------------------------

arithmeticExpression
    : unaryExpression
    | left=arithmeticExpression op=('*'|'/'|'%') right=arithmeticExpression
    | left=arithmeticExpression op=('+'|'-') right=arithmeticExpression
    ;

// --- Unary -----------------------------------------------------------------

unaryExpression
    : postfixExpression                                      #unaryValueExpression
    | op='!' postfixExpression                               #unaryNotExpression
    | op='-' postfixExpression                               #unaryNegationExpression
    | op='~' postfixExpression                               #unaryBitwiseNotExpression
    ;

// --- Postfix (index access, field access) ----------------------------------
// Index access uses [...], field access uses dot notation.
// These chain left-to-right.
//
// Examples:
//   items[0]
//   matrix[i][j]
//   items[0].name
//   people[i].address.city
// ---------------------------------------------------------------------------

postfixExpression
    : primaryExpression (postfixOp)*
    ;

postfixOp
    : '[' expression ']'                                     #indexOp
    | '.' IDENTIFIER                                         #fieldAccessOp
    ;

// --- Primary expressions ---------------------------------------------------

primaryExpression
    : variableReference                                      #primaryVariableReference
    | functionCall                                           #primaryFunctionCall
    | objectCreation                                         #primaryObjectCreation
    | '(' expression ',' expression (',' expression)* ')'    #primaryTupleLiteral
    | '(' expression ')'                                     #primaryGroupedExpression
    | literal                                                #primaryLiteralExpression
    ;

// --- Object creation -------------------------------------------------------
//
// Examples:
//   Point { x: 1.0, y: 2.0 }
//   Pair<int, string> { first: 1, second: "hello" }
// ---------------------------------------------------------------------------

objectCreation
    : IDENTIFIER ('<' type (',' type)* '>')? '{' (fieldAssignment (',' fieldAssignment)*)? '}'
    ;

fieldAssignment
    : IDENTIFIER ':' expression
    ;

// --- Function call ---------------------------------------------------------
// Also used for enum variant construction: Ok(42), Some("hello")
//
// Examples:
//   println("hello")
//   range(10)
//   math:sqrt(2.0)
//   Ok(42)
//   Err("not found")
// ---------------------------------------------------------------------------

functionCall
    : functionName '(' argumentList? ')'
    ;

functionName
    : (IDENTIFIER ':')? name
    ;

argumentList
    : expression (',' expression)*
    ;

// --- Variable reference ----------------------------------------------------

variableReference
    : qualifiedName
    ;

// --- Qualified and dotted names --------------------------------------------

dottedName
    : name ('.' name)*
    ;

qualifiedName
    : (IDENTIFIER ':')? dottedName
    ;

name
    : IDENTIFIER
    ;

// ---------------------------------------------------------------------------
// Literals
// ---------------------------------------------------------------------------

literal
    : booleanLiteral                                         #literalBoolean
    | stringLiteral                                          #literalString
    | numericLiteral                                         #literalNumeric
    | listLiteral                                            #literalList
    | mapLiteral                                             #literalMap
    | setLiteral                                             #literalSet
    | interpolatedString                                     #literalInterpolated
    ;

// --- List literal ----------------------------------------------------------
//
// Examples:
//   [1, 2, 3]
//   ["a", "b", "c"]
//   []
// ---------------------------------------------------------------------------

listLiteral
    : '[' (expression (',' expression)*)? ']'
    ;

// --- Map literal -----------------------------------------------------------
// JSON-like syntax with colons separating keys from values.
//
// Examples:
//   {"name": "SAFE", "version": "1.0"}
//   {1: "one", 2: "two", 3: "three"}
//   {}
// ---------------------------------------------------------------------------

mapLiteral
    : '{' (mapEntry (',' mapEntry)*)? '}'
    ;

mapEntry
    : expression ':' expression
    ;

// --- Set literal --------------------------------------------------------------
// Clojure-style syntax with #{...} for set construction.
//
// Examples:
//   #{1, 2, 3}
//   #{"a", "b", "c"}
//   #{}
// ---------------------------------------------------------------------------

setLiteral
    : HASH '{' (expression (',' expression)*)? '}'
    ;

// --- String interpolation --------------------------------------------------
// Backtick-delimited strings with ${expr} for embedded expressions.
//
// Examples:
//   `Hello, ${name}!`
//   `The sum is ${a + b}`
//   `Item ${i + 1} of ${len(items)}`
// ---------------------------------------------------------------------------

interpolatedString
    : TEMPLATE_STRING
    ;

// --- Boolean, numeric, string literals -------------------------------------

booleanLiteral
    : BOOLEAN
    ;

numericLiteral
    : NUM_FLOAT
    | NUM_INT
    | NUM_UINT
    ;

stringLiteral
    : STRING
    ;

// ===========================================================================
// Lexer rules
// ===========================================================================

// --- Fragments -------------------------------------------------------------

fragment BACKSLASH : '\\' ;
fragment LETTER    : 'A'..'Z' | 'a'..'z' ;
fragment DIGIT     : '0'..'9' ;
fragment EXPONENT  : ('e' | 'E') ('+' | '-')? DIGIT+ ;
fragment HEXDIGIT  : ('0'..'9' | 'a'..'f' | 'A'..'F') ;

fragment ESC_SEQ
    : ESC_CHAR_SEQ
    | ESC_BYTE_SEQ
    | ESC_UNI_SEQ
    | ESC_OCT_SEQ
    ;

fragment ESC_CHAR_SEQ
    : BACKSLASH ('a'|'b'|'f'|'n'|'r'|'t'|'v'|'"'|'\''|'\\'|'?'|'`'|'$')
    ;

fragment ESC_OCT_SEQ
    : BACKSLASH ('0'..'3') ('0'..'7') ('0'..'7')
    ;

fragment ESC_BYTE_SEQ
    : BACKSLASH ('x' | 'X') HEXDIGIT HEXDIGIT
    ;

fragment ESC_UNI_SEQ
    : BACKSLASH 'u' HEXDIGIT HEXDIGIT HEXDIGIT HEXDIGIT
    | BACKSLASH 'U' HEXDIGIT HEXDIGIT HEXDIGIT HEXDIGIT HEXDIGIT HEXDIGIT HEXDIGIT HEXDIGIT
    ;

// --- Keywords --------------------------------------------------------------

// Control flow
IF       : 'if'       ;
THEN     : 'then'     ;
ELSE     : 'else'     ;
FOR      : 'for'      ;
CASE     : 'case'     ;
DEFAULT  : 'default'  ;
OF       : 'of'       ;
IN       : 'in'       ;
RETURN   : 'return'   ;

// Declarations
PROGRAM  : 'program'  ;
MODULE   : 'module'   ;
TYPE     : 'type'     ;
ENUM     : 'enum'     ;

// Modifiers
CONST    : 'const'    ;
PUBLIC   : 'public'   ;
PRIVATE  : 'private'  ;

// Contracts
REQUIRES  : 'requires'  ;
ENSURES   : 'ensures'   ;
DECREASES : 'decreases' ;

// Block expressions
DO       : 'do'       ;

// Functions
FN       : 'fn'       ;
ARROW    : '->'       ;

// Assertions
ASSERT   : 'assert'   ;

// Range
STEP     : 'step'     ;

// Bounded while loop
WHILE    : 'while'    ;
BOUND    : 'bound'    ;

// Symbols
HASH     : '#'        ;

// --- Literals --------------------------------------------------------------

BOOLEAN
    : 'true'
    | 'false'
    ;

STRING
    : '"' (ESC_SEQ | ~('\\' | '"' | '\n' | '\r'))* '"'
    | '\'' (ESC_SEQ | ~('\\' | '\'' | '\n' | '\r'))* '\''
    | '"""' (ESC_SEQ | ~('\\'))*? '"""'
    | '\'\'\'' (ESC_SEQ | ~('\\'))*? '\'\'\''
    ;

// Template string: backtick-delimited, may contain ${...} interpolations.
// The lexer captures the entire template as one token; the parser is
// responsible for splitting it into literal parts and embedded expressions.
TEMPLATE_STRING
    : '`' (ESC_SEQ | '${' (~'}')* '}' | ~('\\' | '`'))* '`'
    ;

NUM_FLOAT
    : DIGIT+ '.' DIGIT+ EXPONENT?
    | '.' DIGIT+ EXPONENT?
    | DIGIT+ EXPONENT
    ;

NUM_INT
    : (DIGIT+ | '0x' HEXDIGIT+)
    ;

NUM_UINT
    : DIGIT+ ('u' | 'U')
    | '0x' HEXDIGIT+ ('u' | 'U')
    ;

// --- Identifiers -----------------------------------------------------------

IDENTIFIER
    : (LETTER | '_') (LETTER | DIGIT | '_')*
    ;

// --- Whitespace and comments -----------------------------------------------

WHITESPACE : ('\t' | ' ' | '\r' | '\n' | '\u000C')+ -> channel(HIDDEN) ;
COMMENT    : '//' (~'\n')* -> channel(HIDDEN) ;
BLOCK_COMMENT : '/*' .*? '*/' -> channel(HIDDEN) ;
