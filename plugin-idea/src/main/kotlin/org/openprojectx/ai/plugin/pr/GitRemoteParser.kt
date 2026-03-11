package org.openprojectx.ai.plugin.pr

object GitRemoteParser {

    fun parse(remoteUrl: String): RepositoryRef {
        parseBitbucket(remoteUrl)?.let { return it }
        parseGitHub(remoteUrl)?.let { return it }
        parseGitLab(remoteUrl)?.let { return it }

        error("Unsupported git remote URL: $remoteUrl")
    }

    private fun parseBitbucket(remoteUrl: String): RepositoryRef? {
        val ssh = Regex("""git@([^:]+):([^/]+)/(.+?)(\.git)?$""")
        val httpsScm = Regex("""https?://([^/]+)/scm/([^/]+)/(.+?)(\.git)?$""", RegexOption.IGNORE_CASE)

        ssh.matchEntire(remoteUrl)?.let { m ->
            val host = m.groupValues[1]
            if (!looksLikeBitbucketHost(host)) return null

            return RepositoryRef(
                provider = GitHostingProviderType.BITBUCKET,
                host = host,
                projectKey = m.groupValues[2],
                repoSlug = m.groupValues[3]
            )
        }

        httpsScm.matchEntire(remoteUrl)?.let { m ->
            return RepositoryRef(
                provider = GitHostingProviderType.BITBUCKET,
                host = m.groupValues[1],
                projectKey = m.groupValues[2],
                repoSlug = m.groupValues[3]
            )
        }

        return null
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
}