package io.github.krax1337.gsync.update

/**
 * Whether version [candidate] comes after [installed]. Both are `major.minor.patch`, with or
 * without a leading `v`; what follows a hyphen is ignored. A version that does not read that way
 * is never newer.
 */
internal fun isNewer(candidate: String, installed: String): Boolean {
    val theirs = parts(candidate) ?: return false
    val mine = parts(installed) ?: return false
    val different = theirs.indices.firstOrNull { theirs[it] != mine[it] } ?: return false
    return theirs[different] > mine[different]
}

private fun parts(version: String): List<Int>? =
    version.removePrefix("v").substringBefore('-').split('.')
        .map { it.toIntOrNull() ?: return null }
        .takeIf { it.size == 3 }
