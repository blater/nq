package blater.nql.cli;

/** A completely parsed and validated NQL command-line invocation. */
public sealed interface NqlInvocation
    permits RunInvocation, ConvertInvocation, CatalogInvocation,
        CacheInvocation, CapabilitiesInvocation, HelpInvocation, VersionInvocation {
}
