package org.openprojectx.ai.plugin.pr

import kotlin.test.Test
import kotlin.test.assertEquals

class GitRemoteParserTest {

    @Test
    fun `parses bitbucket server https scm remote`() {
        val repo = GitRemoteParser.parse("https://bitbucket.example.com/scm/PROJ/my-repo.git")

        assertEquals(GitHostingProviderType.BITBUCKET, repo.provider)
        assertEquals("bitbucket.example.com", repo.host)
        assertEquals("PROJ", repo.projectKey)
        assertEquals("my-repo", repo.repoSlug)
        assertEquals("https://bitbucket.example.com", repo.apiBaseUrl)
    }

    @Test
    fun `parses bitbucket server https scm remote with context path`() {
        val repo = GitRemoteParser.parse("https://git.example.com/bitbucket/scm/PROJ/my-repo.git")

        assertEquals(GitHostingProviderType.BITBUCKET, repo.provider)
        assertEquals("git.example.com", repo.host)
        assertEquals("PROJ", repo.projectKey)
        assertEquals("my-repo", repo.repoSlug)
        assertEquals("https://git.example.com/bitbucket", repo.apiBaseUrl)
    }

    @Test
    fun `strips user info from bitbucket server https remote`() {
        val repo = GitRemoteParser.parse("https://alice@git.example.com/bitbucket/scm/PROJ/my-repo.git")

        assertEquals(GitHostingProviderType.BITBUCKET, repo.provider)
        assertEquals("git.example.com", repo.host)
        assertEquals("https://git.example.com/bitbucket", repo.apiBaseUrl)
    }

    @Test
    fun `parses bitbucket server ssh remote on default server port`() {
        val repo = GitRemoteParser.parse("ssh://git@git.example.com:7999/PROJ/my-repo.git")

        assertEquals(GitHostingProviderType.BITBUCKET, repo.provider)
        assertEquals("git.example.com", repo.host)
        assertEquals("PROJ", repo.projectKey)
        assertEquals("my-repo", repo.repoSlug)
        assertEquals("https://git.example.com", repo.apiBaseUrl)
    }

    @Test
    fun `parses bitbucket server ssh scm remote`() {
        val repo = GitRemoteParser.parse("ssh://git@git.example.com/scm/PROJ/my-repo.git")

        assertEquals(GitHostingProviderType.BITBUCKET, repo.provider)
        assertEquals("git.example.com", repo.host)
        assertEquals("PROJ", repo.projectKey)
        assertEquals("my-repo", repo.repoSlug)
        assertEquals("https://git.example.com", repo.apiBaseUrl)
    }

    @Test
    fun `parses existing bitbucket scp-like remote`() {
        val repo = GitRemoteParser.parse("git@bitbucket.example.com:PROJ/my-repo.git")

        assertEquals(GitHostingProviderType.BITBUCKET, repo.provider)
        assertEquals("bitbucket.example.com", repo.host)
        assertEquals("PROJ", repo.projectKey)
        assertEquals("my-repo", repo.repoSlug)
        assertEquals("https://bitbucket.example.com", repo.apiBaseUrl)
    }

    @Test
    fun `does not classify github as bitbucket`() {
        val repo = GitRemoteParser.parse("git@github.com:OpenProjectX/ai-test-plugin.git")

        assertEquals(GitHostingProviderType.GITHUB, repo.provider)
        assertEquals("github.com", repo.host)
        assertEquals("OpenProjectX", repo.projectKey)
        assertEquals("ai-test-plugin", repo.repoSlug)
        assertEquals("https://github.com", repo.apiBaseUrl)
    }
}
