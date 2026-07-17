package org.earthsworth.wmatcher.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;
import org.earthsworth.wmatcher.app.WorkspaceController;
import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;
import org.earthsworth.wmatcher.core.model.ChangeKind;
import org.earthsworth.wmatcher.core.model.DiffNode;
import org.earthsworth.wmatcher.core.model.DiffResult;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.earthsworth.wmatcher.core.model.EntityKind;
import org.earthsworth.wmatcher.core.model.MatchResult;
import org.earthsworth.wmatcher.core.model.MatchDecision;
import org.earthsworth.wmatcher.core.model.MatchStatus;
import org.earthsworth.wmatcher.core.model.ScoreBreakdown;
import org.earthsworth.wmatcher.core.project.ProjectUiState;
import org.junit.jupiter.api.Test;

class WorkspacePanelTest {
    @Test
    void constructsWorkspaceViewOnEventDispatchThread() throws Exception {
        ArtifactSnapshot left = snapshot("left.jar");
        ArtifactSnapshot right = snapshot("right.jar");
        WorkspaceController.Workspace workspace = new WorkspaceController.Workspace(left, right,
                new MatchResult(List.of(), Map.of(), java.util.Set.of(), java.util.Set.of()),
                new DiffResult(List.of(), Map.of()), Map.of(), List.of(), List.of(), null, "",
                ProjectUiState.empty());
        WorkspaceController controller = new WorkspaceController();
        AtomicPanel result = new AtomicPanel();

        SwingUtilities.invokeAndWait(() -> result.panel = new WorkspacePanel(controller, workspace, ignored -> { }));

        assertThat(result.panel).isNotNull();
        assertThat(result.panel.getComponentCount()).isGreaterThan(0);
        controller.close();
    }

    @Test
    void keepsExpandedPackagesWhenDiffKeysChange() throws Exception {
        EntityId leftId = EntityId.classId("sample/deep/OldName");
        DiffNode unresolved = new DiffNode("class:L:sample/deep/OldName", "sample/deep/OldName",
                EntityKind.CLASS, leftId, null, java.util.Set.of(ChangeKind.UNRESOLVED));
        DiffNode confirmed = new DiffNode("class:sample/deep/OldName", "sample/deep/OldName → a/b",
                EntityKind.CLASS, leftId, EntityId.classId("a/b"), java.util.Set.of(ChangeKind.RENAMED));
        WorkspaceController.Workspace initial = workspace(unresolved);
        WorkspaceController.Workspace updated = workspace(confirmed);
        WorkspaceController controller = new WorkspaceController();
        AtomicPanel result = new AtomicPanel();

        SwingUtilities.invokeAndWait(() -> {
            result.panel = new WorkspacePanel(controller, initial, ignored -> { });
            TreePath sample = findPath(result.panel, "sample");
            result.panel.treeForTesting().expandPath(sample);
            TreePath deep = findPath(result.panel, "deep");
            result.panel.treeForTesting().expandPath(deep);
            result.panel.refresh(updated);
        });

        SwingUtilities.invokeAndWait(() -> { });
        assertThat(result.panel.treeForTesting().isExpanded(findPath(result.panel, "sample"))).isTrue();
        assertThat(result.panel.treeForTesting().isExpanded(findPath(result.panel, "deep"))).isTrue();
        assertThat(SwingUtilities.isDescendingFrom(
                result.panel.searchForTesting(), result.panel.splitForTesting().getLeftComponent())).isTrue();
        assertThat(result.panel.uiState().expandedTreeKeys())
                .contains("package:classes:sample", "package:classes:sample/deep");
        controller.close();
    }

    @Test
    void showsCurrentConfirmedMappingAndRankedAlternatives() throws Exception {
        EntityId leftId = EntityId.classId("old/Readable");
        EntityId currentRight = EntityId.classId("new/Current");
        EntityId alternativeRight = EntityId.classId("new/Alternative");
        MatchDecision current = new MatchDecision(leftId, currentRight, MatchStatus.AUTO_CONFIRMED,
                new ScoreBreakdown(1.0, Map.of("code", 1.0)));
        MatchDecision currentOption = new MatchDecision(leftId, currentRight, MatchStatus.SUGGESTED,
                current.score());
        MatchDecision alternative = new MatchDecision(leftId, alternativeRight, MatchStatus.SUGGESTED,
                new ScoreBreakdown(0.94, Map.of("code", 0.94)));
        DiffNode node = new DiffNode("class:old/Readable", "old/Readable → new/Current",
                EntityKind.CLASS, leftId, currentRight, java.util.Set.of(ChangeKind.RENAMED));
        WorkspaceController.Workspace workspace = new WorkspaceController.Workspace(
                snapshot("left.jar"), snapshot("right.jar"),
                new MatchResult(List.of(current), Map.of(), Map.of(leftId, List.of(currentOption, alternative)),
                        java.util.Set.of(), java.util.Set.of(alternativeRight)),
                new DiffResult(List.of(node), Map.of()), Map.of(), List.of(), List.of(), null, "",
                ProjectUiState.empty());
        WorkspaceController controller = new WorkspaceController();
        AtomicPanel result = new AtomicPanel();

        SwingUtilities.invokeAndWait(() -> {
            result.panel = new WorkspacePanel(controller, workspace, ignored -> { });
            result.panel.selectForTesting(node);
            result.panel.setMinimapsVisible(false);
        });

        assertThat(result.panel.candidatesForTesting().getRowCount()).isEqualTo(2);
        assertThat(result.panel.candidatesForTesting().getValueAt(0, 0).toString()).isNotBlank();
        assertThat(result.panel.candidatesForTesting().getValueAt(0, 2)).isEqualTo("new/Current");
        assertThat(result.panel.candidatesForTesting().getValueAt(1, 2)).isEqualTo("new/Alternative");
        assertThat(result.panel.minimapsVisibleForTesting()).isFalse();
        controller.close();
    }

    @Test
    void replacementConfirmationCanAcceptOrCancelAnOccupiedTarget() {
        EntityId source = EntityId.classId("old/Source");
        EntityId occupant = EntityId.classId("old/Occupant");
        java.util.concurrent.atomic.AtomicInteger prompts = new java.util.concurrent.atomic.AtomicInteger();

        assertThat(WorkspacePanel.replacementAccepted(source, null, () -> {
            prompts.incrementAndGet();
            return false;
        })).isTrue();
        assertThat(prompts).hasValue(0);
        assertThat(WorkspacePanel.replacementAccepted(source, occupant, () -> false)).isFalse();
        assertThat(WorkspacePanel.replacementAccepted(source, occupant, () -> true)).isTrue();
    }

    private static TreePath findPath(WorkspacePanel panel, String label) {
        for (int row = 0; row < panel.treeForTesting().getRowCount(); row++) {
            TreePath path = panel.treeForTesting().getPathForRow(row);
            if (path.getLastPathComponent().toString().equals(label)) {
                return path;
            }
        }
        throw new AssertionError("Tree path not found: " + label);
    }

    private static TreePath findPathContaining(WorkspacePanel panel, String label) {
        for (int row = 0; row < panel.treeForTesting().getRowCount(); row++) {
            TreePath path = panel.treeForTesting().getPathForRow(row);
            if (path.getLastPathComponent().toString().contains(label)) {
                return path;
            }
        }
        throw new AssertionError("Tree path not found: " + label);
    }

    private static WorkspaceController.Workspace workspace(DiffNode node) {
        return new WorkspaceController.Workspace(snapshot("left.jar"), snapshot("right.jar"),
                new MatchResult(List.of(), Map.of(), java.util.Set.of(node.left()),
                        node.right() == null ? java.util.Set.of() : java.util.Set.of(node.right())),
                new DiffResult(List.of(node), Map.of()), Map.of(), List.of(), List.of(), null, "",
                ProjectUiState.empty());
    }

    private static ArtifactSnapshot snapshot(String name) {
        return new ArtifactSnapshot(Path.of(name), "hash", 0, 0, 0, 21, Map.of(), Map.of(), Map.of());
    }

    private static final class AtomicPanel {
        private WorkspacePanel panel;
    }
}
