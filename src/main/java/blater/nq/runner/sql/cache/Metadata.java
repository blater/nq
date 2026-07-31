package blater.nq.runner.sql.cache;

import blater.nq.inputreader.InputType;

record Metadata(
    String sourcePath,
    String inputType,
    String identityText,
    long createdMillis) {

  CacheSource source() {
    String variant = identityText.lines()
        .filter(line -> line.startsWith("variant="))
        .map(line -> line.substring("variant=".length()))
        .findFirst()
        .orElse("");
    return new CacheSource(sourcePath, InputType.valueOf(inputType), variant);
  }
}
