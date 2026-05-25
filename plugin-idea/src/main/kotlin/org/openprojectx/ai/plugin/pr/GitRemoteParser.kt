package org.openprojectx.ai.plugin.pr

import java.net.URI

object GitRemoteParser {

    fun parse(remoteUrl: String): RepositoryRef {
        parseBitbucket(remoteUrl)?.let { return it }
        parseGitHub(remoteUrl)?.let { return it }
        parseGitLab(remoteUrl)?.let { return it }

        error("Unsupported git remote URL: $remoteUrl")
    }

    private fun parseBitbucket(remoteUrl: String): RepositoryRef? {
        val ssh = Regex("""git@([^:]+):([^/]+)/(.+?)(\.git)?$""")
        parseBitbucketHttp(remoteUrl)?.let { return it }
        parseBitbucketSshUri(remoteUrl)?.let { return it }

        ssh.matchEntire(remoteUrl)?.let { m ->
            val host = m.groupValues[1]
            if (!looksLikeBitbucketHost(host)) return null

            return RepositoryRef(
                provider = GitHostingProviderType.BITBUCKET,
                host = host,
                projectKey = m.groupValues[2],
                repoSlug = trimGitSuffix(m.groupValues[3]),
                apiBaseUrl = "https://$host"
            )
        }

        return null
    }

    private fun parseBitbucketHttp(remoteUrl: String): RepositoryRef? {
        val uri = runCatching { URI(remoteUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        uri.host ?: return null

        val segments = uri.path.trim('/').split('/').filter { it.isNotBlank() }
        val scmIndex = segments.indexOfFirst { it.equals("scm", ignoreCase = true) }
        if (scmIndex >= 0 && segments.size > scmIndex + 2) {
            val contextPath = segments.take(scmIndex).joinToString("/")
            return RepositoryRef(
                provider = GitHostingProviderType.BITBUCKET,
                host = hostWithPort(uri),
                projectKey = segments[scmIndex + 1],
                repoSlug = trimGitSuffix(segments[scmIndex + 2]),
                apiBaseUrl = buildApiBaseUrl(uri, contextPath)
            )
        }

        if (segments.size >= 2 && looksLikeBitbucketHost(uri.host.orEmpty())) {
            return RepositoryRef(
                provider = GitHostingProviderType.BITBUCKET,
                host = hostWithPort(uri),
                projectKey = segments[0],
                repoSlug = trimGitSuffix(segments[1]),
                apiBaseUrl = buildApiBaseUrl(uri, "")
            )
        }

        return null
    }

    private fun parseBitbucketSshUri(remoteUrl: String): RepositoryRef? {
        val uri = runCatching { URI(remoteUrl) }.getOrNull() ?: return null
        if (!uri.scheme.equals("ssh", ignoreCase = true)) return null
        val host = uri.host ?: return null
        val segments = uri.path.trim('/').split('/').filter { it.isNotBlank() }
        val hasScmSegment = segments.firstOrNull().equals("scm", ignoreCase = true)
        if (!looksLikeBitbucketHost(host) && !hasScmSegment && uri.port != 7999) return null
        val normalizedSegments = if (segments.firstOrNull().equals("scm", ignoreCase = true)) {
            segments.drop(1)
        } else {
            segments
        }
        if (normalizedSegments.size < 2) return null

        return RepositoryRef(
            provider = GitHostingProviderType.BITBUCKET,
            host = host,
            projectKey = normalizedSegments[0],
            repoSlug = trimGitSuffix(normalizedSegments[1]),
            apiBaseUrl = "https://$host"
        )
    }

    private fun parseGitHub(remoteUrl: String): RepositoryRef? {
        val ssh = Regex("""git@github\.com:([^/]+)/(.+?)(\.git)?$""", RegexOption.IGNORE_CASE)
        val https = Regex("""https?://github\.com/([^/]+)/(.+?)(\.git)?$""", RegexOption.IGNORE_CASE)

        ssh.matchEntire(remoteUrl)?.let { m ->
            return RepositoryRef(
                provider = GitHostingProviderType.GITHUB,
                host = "github.com",
                projectKey = m.groupValues[1],
                repoSlug = m.groupValues[2]
            )
        }

        https.matchEntire(remoteUrl)?.let { m ->
            return RepositoryRef(
                provider = GitHostingProviderType.GITHUB,
                host = "github.com",
                projectKey = m.groupValues[1],
                repoSlug = m.groupValues[2]
            )
        }

        return null
    }

    private fun parseGitLab(remoteUrl: String): RepositoryRef? {
        val ssh = Regex("""git@([^:]+):([^/]+)/(.+?)(\.git)?$""")
        val https = Regex("""https?://([^/]+)/([^/]+)/(.+?)(\.git)?$""", RegexOption.IGNORE_CASE)

        ssh.matchEntire(remoteUrl)?.let { m ->
            val host = m.groupValues[1]
            if (!host.contains("gitlab", ignoreCase = true)) return null

            return RepositoryRef(
                provider = GitHostingProviderType.GITLAB,
                host = host,
                projectKey = m.groupValues[2],
                repoSlug = m.groupValues[3]
            )
        }

        https.matchEntire(remoteUrl)?.let { m ->
            val host = m.groupValues[1]
            if (!host.contains("gitlab", ignoreCase = true)) return null

            return RepositoryRef(
                provider = GitHostingProviderType.GITLAB,
                host = host,
                projectKey = m.groupValues[2],
                repoSlug = m.groupValues[3]
            )
        }

        return null
    }

    private fun looksLikeBitbucketHost(host: String): Boolean {
        return host.contains("bitbucket", ignoreCase = true)
    }

    private fun trimGitSuffix(value: String): String {
        return value.removeSuffix(".git")
    }

    private fun buildApiBaseUrl(uri: URI, contextPath: String): String {
        val base = "${uri.scheme}://${hostWithPort(uri)}"
        return if (contextPath.isBlank()) base else "$base/$contextPath"
    }

    private fun hostWithPort(uri: URI): String {
        return if (uri.port > 0) "${uri.host}:${uri.port}" else uri.host
    }
}
