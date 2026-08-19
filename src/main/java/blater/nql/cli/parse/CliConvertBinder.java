package blater.nql.cli.parse;

import blater.nql.cli.ConvertInvocation;
import blater.nql.cli.DataInput;
import blater.nql.outputwriter.OutputType;

/** Binds document-conversion invocations. */
final class CliConvertBinder {
  private CliConvertBinder() {
  }

  static ConvertInvocation bind(CliBindingSupport support, CliParser.RawArguments raw) {
    CliOptionValidator.validateConvertOptionOwnership(raw);
    CliParser.reject(raw.inputFile != null && raw.inputText != null,
        "conversion accepts exactly one data source");
    if (raw.positionals.size() > 1) {
      throw CliParser.usage("conversion accepts exactly one data source");
    }
    CliParser.reject(
        (raw.inputFile != null || raw.inputText != null) && !raw.positionals.isEmpty(),
        "positional data conflicts with its named input option");
    String positional = raw.positionals.isEmpty() ? null : raw.positionals.getFirst();
    DataInput input = support.dataInput(raw, positional, true);
    support.validateParquetOptions(raw, input.format());
    return new ConvertInvocation(
        input,
        raw.output == null ? OutputType.JSON : CliValueParser.outputType(raw.output),
        support.invocationOptions(raw));
  }
}
