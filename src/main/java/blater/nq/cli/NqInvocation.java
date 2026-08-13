package blater.nq.cli;

/** A completely parsed and validated NQ command-line invocation. */
public sealed interface NqInvocation
    permits RunInvocation, ConvertInvocation, CatalogInvocation,
        CacheInvocation, CapabilitiesInvocation, HelpInvocation, VersionInvocation {
}
