package mod.chiselsandbits.api.multistate.accessor.identifier;

/**
 * Optional contract for area accessors that can provide a stable, revisioned shape identifier.
 *
 * Implementations should return the same identifier instance while the revision remains unchanged,
 * and return a different identifier when the revision changes.
 */
public interface IRevisionedAreaShapeIdentifierProvider {

    /**
     * Returns the current storage revision used for cache invalidation.
     *
     * @return The current revision.
     */
    long getShapeIdentifierRevision();

    /**
     * Returns an identifier for the current revision.
     *
     * @return A cached shape identifier aligned with the current revision.
     */
    IAreaShapeIdentifier getCachedShapeIdentifier();
}
