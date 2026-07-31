package blater.nq.runner.sql.cache;

import blater.nq.inputreader.InputType;

record Metadata(
    String sourcePath,
    String inputType,
    String identityText,
    long createdMillis) {

  boolean databaseStructure() {
    return "DATABASE_STRUCTURE".equals(inputType);
  }

  CacheSource source() {
    String variant = identityText.lines()
        .filter(line -> line.startsWith("variant="))
        .map(line -> line.substring("variant=".length()))
        .findFirst()
        .orElse("");
    int layoutStart = identityText.indexOf("layoutVersion=");
    String materializationKey = layoutStart < 0 ? "" : identityText.substring(layoutStart);
    return new CacheSource(sourcePath, InputType.valueOf(inputType), variant, materializationKey);
  }
}
