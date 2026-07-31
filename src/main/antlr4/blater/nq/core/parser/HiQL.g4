grammar HiQL;

// Refactor note: keep this grammar under blater/nq so generated parser
// classes stay in the package imported by the application code.

// ─── Parser ───────────────────────────────────────────────────────────────────

script
    : scriptItem* EOF
    ;

scriptItem
    : outputDirective statementDelimiter
    | statementBlock
    ;

outputDirective
    : K_OUTPUT outputFormat
    ;

outputFormat
    : K_XML
    | K_JSON
    | K_JSONL
    | K_CSV
    | K_YAML
    | K_MARKDOWN
    ;

statementBlock
    : body statementDelimiter handlerBlock*
    ;

statementDelimiter
    : SEMI TERM?
    | TERM
    ;

body
    : autoCommit
    | catalog
    | capture
    | dmlUpdate
    | dmlInsert
    | dmlDelete
    | execProc
    | literalSql
    | selectStatement
    ;

autoCommit
    : K_AUTOCOMMIT ( K_ON | K_OFF )
    ;

catalog
    : K_CATALOG catalogPattern?
    ;

catalogPattern
    : ( name | STAR )+
    ;

capture
    : K_CAPTURE STRING rawSql
    ;

dmlUpdate
    : K_UPDATE name ( K_FROM K_TEMP STRING )? K_SET dmlAssignmentList ( K_WHERE dmlPredicateList )? returnsClause?
    ;

dmlInsert
    : K_INSERT K_INTO name ( K_FROM K_TEMP STRING )?
      ( LPAREN nameList RPAREN )?
      ( K_VALUES LPAREN dmlExprList RPAREN | selectStatement )
      returnsClause?
    ;

dmlDelete
    : K_DELETE K_FROM name ( K_FROM K_TEMP STRING )? ( K_WHERE dmlPredicateList )?
    ;

nameList
    : name ( COMMA name )*
    ;

dmlAssignmentList
    : dmlAssignment ( COMMA dmlAssignment )*
    ;

dmlAssignment
    : name EQ dmlExpr
    ;

dmlPredicateList
    : dmlPredicate ( K_AND dmlPredicate )*
    ;

dmlPredicate
    : name EQ dmlExpr
    ;

dmlExprList
    : dmlExpr ( COMMA dmlExpr )*
    ;

dmlExpr
    : dmlExprAtom+
    ;

dmlExprAtom
    : dmlSource
    | LPAREN dmlExprInside* RPAREN
    | ~( COMMA | K_WHERE | K_AND | K_RETURNS | LPAREN | RPAREN | SEMI | TERM )
    ;

dmlExprInside
    : dmlSource
    | LPAREN dmlExprInside* RPAREN
    | ~( LPAREN | RPAREN | SEMI | TERM )
    ;

dmlSource
    : LBRACE path RBRACE
    ;

returnsClause
    : K_RETURNS returnMapping ( COMMA returnMapping )*
    ;

returnMapping
    : name K_INTO LBRACE path RBRACE
    ;
execProc
    : K_EXECUTE K_PROCEDURE name LPAREN mappingList RPAREN
    ;

handlerBlock
    : ( K_ONERROR | K_ONWARNING ) LPAREN STRING ( COMMA handlerFlag )* RPAREN statementDelimiter
    ;

handlerFlag
    : K_ABORT | K_ROLLBACK
    ;

mappingList
    : mapping ( COMMA mapping )*
    ;

mapping
    : name EQ LBRACE mappingItem ( COMMA mappingItem )* RBRACE
    ;

// All items in a mapping block are uniform. STRING or path sets the source; name:inputValue
// are options (key, uid, volatile, literal, xpath).
mappingItem
    : STRING                // quoted literal source: 'NEW'
    | name COLON optVal     // named option: key:1  literal:'${X}'  uid:true
    | path                  // xpath source: message.dish.@id  or bare column: personid
    ;

optVal
    : STRING
    | INTEGER
    | boolVal
    ;

boolVal
    : K_TRUE | K_FALSE
    ;

path
    : name ( DOT pathSegment )*
    ;

pathSegment
    : AT name
    | name
    ;

// ─── select output ──────────────────────────────────────────────────────────
//
// `select` reads a SQL SELECT (or unioned series) and routes columns into an
// output hierarchy/properties. SQL between markers is opaque token soup — we recognise
// only the markers that drive mapping (INTO {path}, xmlunion, ORDER BY ...
// createsNew {path}). Parens are paren-balanced via sqlInside so that
// subquery-internal occurrences of those markers are ignored.

selectStatement
    : selectBranch ( ( hierarchyUnion | K_XMLUNION ) selectBranch )* orderByClause? structureClause?
    ;

hierarchyUnion
    : K_HIERARCHY K_UNION
    ;

usingClause
    : K_USING usingItem+
    ;

usingItem
    : K_SCHEMA STRING
    | K_NAMESPACE EQ ( STRING | QUOTED_IDENTIFIER )
    | K_XMLROOT EQ name
    ;

selectBranch
    : K_SELECT usingClause? selectItem ( COMMA selectItem )* sqlTail?
    ;

selectItem
    : selectExpr sqlAlias? mappingAlias?
    ;

sqlAlias
    : K_AS name
    ;

mappingAlias
    : K_INTO LBRACE path ( COLON name ( LPAREN name RPAREN )? )? RBRACE nullOutputPolicy?
    ;

nullOutputPolicy
    : K_ABSENT K_ON K_NULL
    ;

orderByClause
    : K_ORDER K_BY orderItem ( COMMA orderItem )*
    ;

orderItem
    : orderExpr ( K_ASC | K_DESC )? ( createsNewClause ( AMP createsNewClause )* )?
    ;

orderExpr
    : orderExprAtom+
    ;

orderExprAtom
    : LPAREN sqlInside* RPAREN
    | ~( COMMA
       | K_ASC | K_DESC
       | K_STRUCTURE | K_HIERARCHY | K_CREATESNEW
       | K_GROUP | K_HAVING | K_XMLUNION
       | LPAREN | RPAREN
       | SEMI | TERM )
    ;

structureClause
    : K_STRUCTURE structureItem ( COMMA structureItem )*
    ;

structureItem
    : LBRACE path RBRACE K_KEY LPAREN structureKeyExpr ( COMMA structureKeyExpr )* RPAREN
    ;

structureKeyExpr
    : structureKeyExprAtom+
    ;

structureKeyExprAtom
    : LPAREN sqlInside* RPAREN
    | ~( COMMA | LPAREN | RPAREN | SEMI | TERM )
    ;

createsNewClause
    : K_CREATESNEW LBRACE path RBRACE
    ;

// One select-item expression. Stops at COMMA (next item), AS (SQL alias), the
// `into {` mapping marker, or any keyword that ends the select-list (FROM/WHERE/
// GROUP/HAVING/ORDER) at depth 0. Parens are nested via sqlInside.
//
// `into` is significant only when followed by `{`: the predicate stops the atom
// loop at `into {` so mappingAlias can match, but lets `into <name>` (e.g. vendor
// `SELECT ... INTO table`) flow through as ordinary passthrough SQL.
selectExpr
    : selectExprAtom+
    ;

selectExprAtom
    : LPAREN sqlInside* RPAREN
    | { !(_input.LT(1).getType() == K_INTO && _input.LT(2).getType() == LBRACE) }?
      ~( COMMA
       | K_AS
       | K_FROM | K_WHERE | K_GROUP | K_HAVING
       | K_ORDER | K_XMLUNION | K_HIERARCHY | K_STRUCTURE
       | LPAREN | RPAREN
       | SEMI | TERM )
    ;

// Everything from FROM (or wherever the first non-select-list token is) up to
// xmlunion / top-level order by / statement delimiter. Parens nested via sqlInside.
sqlTail
    : sqlTailAtom+
    ;

sqlTailAtom
    : LPAREN sqlInside* RPAREN
    | ~( K_XMLUNION | K_HIERARCHY | K_STRUCTURE | K_ORDER | LPAREN | RPAREN | SEMI | TERM )
    ;

// Opaque contents of a parenthesised group. Anything goes; just keep parens
// balanced. TERM ends a statement so it stops nesting too.
sqlInside
    : LPAREN sqlInside* RPAREN
    | ~( LPAREN | RPAREN | TERM )
    ;

// Keywords are usable as identifiers in paths and names to avoid reserved-word conflicts.
name
    : IDENT
    | QUOTED_IDENTIFIER
    | keyword
    ;

keyword
    : K_UPDATE | K_TABLE | K_INSERT | K_DELETE | K_FROM | K_TEMP
    | K_USING | K_SET | K_CAPTURE | K_EXECUTE | K_PROCEDURE
    | K_OUTPUT | K_XML | K_JSON | K_CSV | K_YAML | K_MARKDOWN
    | K_AUTOCOMMIT | K_CATALOG | K_ON | K_OFF | K_TRUE | K_FALSE
    | K_ABORT | K_ROLLBACK | K_ONERROR | K_ONWARNING
    | K_XMLUNION | K_XMLROOT | K_SCHEMA | K_NAMESPACE | K_HIERARCHY | K_UNION
    | K_SELECT | K_INTO | K_WHERE | K_GROUP | K_HAVING
    | K_ORDER | K_BY | K_ASC | K_DESC | K_STRUCTURE | K_KEY | K_CREATESNEW
    | K_AS | K_ABSENT | K_NULL | K_VALUES | K_AND | K_LITERAL
    | K_RETURNS
    ;

rawSql
    : rawSqlToken+
    ;

rawSqlToken
    : ~( TERM | SEMI )
    ;

literalSql
    : K_LITERAL rawSql
    ;


// ─── Lexer ────────────────────────────────────────────────────────────────────

// Statement delimiters and structural punctuation
TERM         : '\\' G ;
SEMI         : ';' ;
LPAREN       : '(' ;
RPAREN       : ')' ;
LBRACE       : '{' ;
RBRACE       : '}' ;
LBRACKET     : '[' ;
RBRACKET     : ']' ;
COMMA        : ',' ;
DOT          : '.' ;
EQ           : '=' ;
COLON        : ':' ;
AT           : '@' ;
AMP          : '&' ;

// SQL operators (needed so they lex cleanly inside rawSql / xmlselect bodies)
LTEQ         : '<=' ;
GTEQ         : '>=' ;
NEQ          : '<>' | '!=' ;
LT           : '<' ;
GT           : '>' ;
PLUS         : '+' ;
MINUS        : '-' ;
STAR         : '*' ;
SLASH        : '/' ;
PERCENT      : '%' ;
PIPE2        : '||' ;
PIPE         : '|' ;
HAT          : '^' ;
TILDE_TOK    : '~' ;
QUESTION     : '?' ;
HASH         : '#' ;
DOLLAR       : '$' ;

// DML script keywords (case-insensitive via fragment rules)
K_AUTOCOMMIT : A U T O C O M M I T ;
K_CATALOG    : C A T A L O G ;
K_ON         : O N ;
K_OFF        : O F F ;
K_CAPTURE    : C A P T U R E ;
K_UPDATE     : U P D A T E ;
K_TABLE      : T A B L E ;
K_FROM       : F R O M ;
K_TEMP       : T E M P ;
K_USING      : U S I N G ;
K_SET        : S E T ;
K_INSERT     : I N S E R T ;
K_DELETE     : D E L E T E ;
K_EXECUTE    : E X E C U T E ;
K_PROCEDURE  : P R O C E D U R E ;
K_ONERROR    : O N E R R O R ;
K_ONWARNING  : O N W A R N I N G ;
K_ABORT      : A B O R T ;
K_ROLLBACK   : R O L L B A C K ;
K_TRUE       : T R U E ;
K_FALSE      : F A L S E ;
K_OUTPUT     : O U T P U T ;
K_XML        : X M L ;
K_JSONL      : J S O N L ;
K_JSON       : J S O N ;
K_CSV        : C S V ;
K_YAML       : Y A M L ;
K_MARKDOWN   : M A R K D O W N ;

// xmlselect keywords
K_XMLSELECT  : X M L S E L E C T ;
K_XMLUNION   : X M L U N I O N ;
K_HIERARCHY  : H I E R A R C H Y ;
K_UNION      : U N I O N ;
K_XMLROOT    : X M L R O O T ;
K_SCHEMA     : S C H E M A ;
K_NAMESPACE  : N A M E S P A C E ;
K_SELECT     : S E L E C T ;
K_INTO       : I N T O ;
K_WHERE      : W H E R E ;
K_GROUP      : G R O U P ;
K_HAVING     : H A V I N G ;
K_ORDER      : O R D E R ;
K_BY         : B Y ;
K_AS         : A S ;
K_ASC        : A S C ;
K_DESC       : D E S C ;
K_CREATESNEW : C R E A T E S N E W ;
K_STRUCTURE  : S T R U C T U R E ;
K_KEY        : K E Y ;
K_ABSENT     : A B S E N T ;
K_NULL       : N U L L ;
K_VALUES     : V A L U E S ;
K_AND        : A N D ;
K_LITERAL    : L I T E R A L ;
K_RETURNS    : R E T U R N S ;

STRING
    : '\'' ( ~'\'' | '\'\'' )* '\''
    ;

QUOTED_IDENTIFIER
    : '"' ~[\r\n"]* '"'
    | '[' ~[\r\n\]]* ']'
    | '`' ~[\r\n`]* '`'
    ;

INTEGER
    : [0-9]+ ( '.' [0-9]+ )? ( [eE] [+-]? [0-9]+ )?
    ;

IDENT
    : [a-zA-Z_$] [a-zA-Z_$0-9]*
    ;

WS
    : [ \t\r\n]+ -> skip
    ;

LINE_COMMENT
    : '--' ~[\r\n]* -> skip
    ;

BLOCK_COMMENT
    : '/*' .*? '*/' -> skip
    ;

// ─── Case-insensitive letter fragments ───────────────────────────────────────

fragment A : [aA] ;
fragment B : [bB] ;
fragment C : [cC] ;
fragment D : [dD] ;
fragment E : [eE] ;
fragment F : [fF] ;
fragment G : [gG] ;
fragment H : [hH] ;
fragment I : [iI] ;
fragment J : [jJ] ;
fragment K : [kK] ;
fragment L : [lL] ;
fragment M : [mM] ;
fragment N : [nN] ;
fragment O : [oO] ;
fragment P : [pP] ;
fragment Q : [qQ] ;
fragment R : [rR] ;
fragment S : [sS] ;
fragment T : [tT] ;
fragment U : [uU] ;
fragment V : [vV] ;
fragment W : [wW] ;
fragment X : [xX] ;
fragment Y : [yY] ;
fragment Z : [zZ] ;
