package com.noamv.localllm.engine

/**
 * Transfers ownership only after initialization succeeds.
 *
 * `Engine(config).apply { initialize() }` leaks the just-created native engine when
 * initialize throws. This helper keeps ownership local until success and always closes
 * the resource on a failed or cancelled initialization.
 */
internal inline fun <T : AutoCloseable> initializeOwnedEngine(
    create: () -> T,
    initialize: (T) -> Unit,
): T {
    val created = create()
    try {
        initialize(created)
        return created
    } catch (error: Throwable) {
        runCatching { created.close() }
        throw error
    }
}
