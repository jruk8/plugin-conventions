package com.jruk8.gradle

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Guards the snapshot changelog contract in the publish workflow.
 *
 * <p>Snapshot pre-releases must carry cumulative notes for everything since
 * the last release, headed with the snapshot version, and the floating
 * {@code latest-snapshot} tag must advance to the built commit.
 */
class PublishWorkflowTest {

    private static String workflowText() {
        File file = new File('.github/workflows/publish.yml')
        assertTrue(file.isFile(), 'publish.yml not found at ' + file.absolutePath)
        return file.text
    }

    @Test
    void snapshotNotesCoverUnreleasedCommitsWithSnapshotHeader() {
        String text = workflowText()
        assertTrue(text.contains('--unreleased --tag v${{ steps.version.outputs.version }}'),
                'snapshot notes must render unreleased commits under the snapshot version')
        int tagRemoval = text.indexOf('git tag -d latest-snapshot')
        assertTrue(tagRemoval >= 0, 'stale local snapshot tag must be removed before notes generation')
        assertTrue(tagRemoval < text.indexOf('--unreleased'),
                'stale tag removal must run before snapshot notes generation')
    }

    @Test
    void releaseNotesStillUseLatest() {
        assertTrue(workflowText().contains('args: --latest --strip header'),
                'release notes must keep using --latest')
    }

    @Test
    void snapshotCleanupDeletesRemoteTag() {
        String text = workflowText()
        int cleanup = text.indexOf('- name: Delete previous snapshot release and tag')
        assertTrue(cleanup >= 0, 'snapshot cleanup step missing')
        int publish = text.indexOf('- name: Publish GitHub Pre-release (snapshot)', cleanup)
        assertTrue(publish > cleanup, 'snapshot pre-release step missing')
        String block = text.substring(cleanup, publish)
        assertTrue(block.contains('deleteRef'), 'snapshot cleanup must delete the remote tag')
        assertTrue(block.contains('tags/${tagName}'), 'snapshot cleanup must delete tags/latest-snapshot')
    }

    @Test
    void snapshotReleaseUsesSnapshotNotesAndBuiltCommit() {
        String text = workflowText()
        int publish = text.indexOf('- name: Publish GitHub Pre-release (snapshot)')
        assertTrue(publish >= 0, 'snapshot pre-release step missing')
        String block = text.substring(publish)
        assertTrue(block.contains('steps.snapshot-changelog.outputs.content'),
                'snapshot release body must use the snapshot notes')
        assertTrue(block.contains('target_commitish: ${{ github.sha }}'),
                'recreated snapshot tag must point at the built commit')
    }
}
