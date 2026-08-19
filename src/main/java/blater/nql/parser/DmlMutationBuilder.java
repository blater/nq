package blater.nql.parser;

import blater.nql.core.parser.HiQLParser;
import blater.nql.parser.script.NestStatement;
import blater.nql.parser.script.ReturnMapping;
import blater.nql.runner.sql.domain.InputToColumnMap;

import java.util.ArrayList;
import java.util.List;

import static blater.nql.parser.script.NestSqlStatementType.INSERT;
import static blater.nql.parser.script.NestSqlStatementType.UPDATE;
import static blater.nql.util.Log.FATAL_SYNTAX_ERROR;
import static blater.nql.util.Log.fatal;

/** Builds INSERT and UPDATE statements with mapped input sources. */
final class DmlMutationBuilder {
  private DmlMutationBuilder() {
  }

  static NestStatement buildUpdate(HiQLParser.DmlUpdateContext context) {
    List<ReturnMapping> returnMappings =
        DmlBuilder.buildReturnMappings(context.returnsClause());
    List<HiQLParser.DmlExprContext> expressions = updateExpressions(context);
    if (DmlBuilder.hasNoMappedSources(expressions)) {
      rejectReturnsWithoutInput(returnMappings);
      return NestStatement.literal(ParseUtils.textOf(context).trim());
    }
    return NestStatement.dml(
        UPDATE,
        context.name().getText(),
        DmlBuilder.sourceRowsetName(context.STRING()),
        updateMappings(context),
        returnMappings);
  }

  static NestStatement buildInsert(HiQLParser.DmlInsertContext context) {
    List<ReturnMapping> returnMappings =
        DmlBuilder.buildReturnMappings(context.returnsClause());
    if (context.selectStatement() != null) {
      validateInsertSourceSelect(context.selectStatement(), returnMappings);
      return NestStatement.literal(ParseUtils.textOf(context).trim());
    }
    if (DmlBuilder.hasNoMappedSources(context.dmlExprList().dmlExpr())) {
      rejectReturnsWithoutInput(returnMappings);
      return NestStatement.literal(ParseUtils.textOf(context).trim());
    }
    return NestStatement.dml(
        INSERT,
        context.name().getText(),
        DmlBuilder.sourceRowsetName(context.STRING()),
        insertMappings(context),
        returnMappings);
  }

  private static List<HiQLParser.DmlExprContext> updateExpressions(
      HiQLParser.DmlUpdateContext context) {
    List<HiQLParser.DmlExprContext> expressions = new ArrayList<>();
    context.dmlAssignmentList().dmlAssignment().stream()
        .map(HiQLParser.DmlAssignmentContext::dmlExpr)
        .forEach(expressions::add);
    if (context.dmlPredicateList() != null) {
      context.dmlPredicateList().dmlPredicate().stream()
          .map(HiQLParser.DmlPredicateContext::dmlExpr)
          .forEach(expressions::add);
    }
    return expressions;
  }

  private static List<InputToColumnMap> updateMappings(
      HiQLParser.DmlUpdateContext context) {
    boolean fromTempRowset = context.STRING() != null;
    List<InputToColumnMap> mappings = new ArrayList<>();
    for (HiQLParser.DmlAssignmentContext assignment
        : context.dmlAssignmentList().dmlAssignment()) {
      mappings.add(DmlBuilder.buildExpressionMapping(
          assignment.name().getText(), assignment.dmlExpr(), false, fromTempRowset));
    }
    if (context.dmlPredicateList() != null) {
      for (HiQLParser.DmlPredicateContext predicate
          : context.dmlPredicateList().dmlPredicate()) {
        mappings.add(DmlBuilder.buildExpressionMapping(
            predicate.name().getText(), predicate.dmlExpr(), true, fromTempRowset));
      }
    }
    return mappings;
  }

  private static List<InputToColumnMap> insertMappings(
      HiQLParser.DmlInsertContext context) {
    List<HiQLParser.NameContext> names = context.nameList() == null
        ? List.of()
        : context.nameList().name();
    List<HiQLParser.DmlExprContext> values = context.dmlExprList().dmlExpr();
    if (!names.isEmpty() && names.size() != values.size()) {
      fatal(FATAL_SYNTAX_ERROR, "INSERT column count does not match value count.");
    }
    boolean fromTempRowset = context.STRING() != null;
    List<InputToColumnMap> mappings = new ArrayList<>();
    for (int index = 0; index < values.size(); index++) {
      String sqlName = names.isEmpty() ? "$" + (index + 1) : names.get(index).getText();
      mappings.add(DmlBuilder.buildExpressionMapping(
          sqlName, values.get(index), false, fromTempRowset));
    }
    return mappings;
  }

  private static void rejectReturnsWithoutInput(List<ReturnMapping> returnMappings) {
    if (!returnMappings.isEmpty()) {
      fatal(FATAL_SYNTAX_ERROR,
          "DML returns requires at least one mapped input source.");
    }
  }

  private static void validateInsertSourceSelect(
      HiQLParser.SelectStatementContext context, List<ReturnMapping> returnMappings) {
    if (!returnMappings.isEmpty()) {
      fatal(FATAL_SYNTAX_ERROR, "INSERT ... SELECT does not support returns.");
    }
    if (context.selectBranch().size() > 1) {
      fatal(FATAL_SYNTAX_ERROR, "insert select source cannot use hierarchy union.");
    }
    if (context.structureClause() != null) {
      fatal(FATAL_SYNTAX_ERROR, "insert select source cannot use structure.");
    }
    for (HiQLParser.SelectBranchContext branch : context.selectBranch()) {
      if (branch.usingClause() != null) {
        fatal(FATAL_SYNTAX_ERROR,
            "insert select source cannot use 'using' metadata.");
      }
      for (HiQLParser.SelectItemContext item : branch.selectItem()) {
        if (item.mappingAlias() != null) {
          fatal(FATAL_SYNTAX_ERROR,
              "insert select source cannot use hierarchy mapping aliases.");
        }
      }
    }
  }
}
