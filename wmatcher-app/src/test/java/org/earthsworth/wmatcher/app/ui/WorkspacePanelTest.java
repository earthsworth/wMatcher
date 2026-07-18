package org.earthsworth.wmatcher.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import javax.swing.JTree;
import javax.swing.tree.TreePath;
import org.earthsworth.wmatcher.app.WorkspaceController;
import org.earthsworth.wmatcher.core.model.ArtifactSnapshot;
import org.earthsworth.wmatcher.core.model.ChangeKind;
import org.earthsworth.wmatcher.core.model.ClassClassification;
import org.earthsworth.wmatcher.core.model.ClassPair;
import org.earthsworth.wmatcher.core.model.ComparisonOverrides;
import org.earthsworth.wmatcher.core.model.DiffNode;
import org.earthsworth.wmatcher.core.model.DiffResult;
import org.earthsworth.wmatcher.core.model.EntityId;
import org.earthsworth.wmatcher.core.model.EntityKind;
import org.earthsworth.wmatcher.core.model.MatchResult;
import org.earthsworth.wmatcher.core.model.MatchDecision;
import org.earthsworth.wmatcher.core.model.MatchStatus;
import org.earthsworth.wmatcher.core.model.ResolutionStatus;
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
        assertThat(result.panel.summaryCardCountForTesting()).isEqualTo(5);
        assertThat(result.panel.canonicalNameOptionsForTesting()).containsExactly(
                "LEFT -> RIGHT", "RIGHT -> LEFT", "Disabled");
        assertThat(result.panel.canonicalNamesDirection())
                .isEqualTo(WorkspaceController.CanonicalNamesDirection.DISABLED);
        controller.close();
    }

    @Test
    void treeSearchUsesCaseSensitiveContainsMatching() throws Exception {
        EntityId alpha = EntityId.classId("Alpha");
        EntityId alphaBeta = EntityId.classId("AlphaBeta");
        DiffNode alphaNode = new DiffNode("alpha", "Alpha", EntityKind.CLASS,
                alpha, null, java.util.Set.of(ChangeKind.UNRESOLVED));
        DiffNode alphaBetaNode = new DiffNode("alpha-beta", "AlphaBeta", EntityKind.CLASS,
                alphaBeta, null, java.util.Set.of(ChangeKind.UNRESOLVED));
        WorkspaceController.Workspace workspace = new WorkspaceController.Workspace(
                snapshot("left.jar"), snapshot("right.jar"),
                new MatchResult(List.of(), Map.of(), java.util.Set.of(alpha, alphaBeta), java.util.Set.of()),
                new DiffResult(List.of(alphaNode, alphaBetaNode), Map.of()), Map.of(),
                List.of(), List.of(), null, "", ProjectUiState.empty());
        WorkspaceController controller = new WorkspaceController();
        AtomicPanel result = new AtomicPanel();

        SwingUtilities.invokeAndWait(() -> {
            result.panel = new WorkspacePanel(controller, workspace, ignored -> { });
            result.panel.searchForTesting().setText("Alpha");
        });

        assertThat(result.panel.stableTreeKeysForTesting()).contains(
                "entity:L:CLASS:Alpha", "entity:L:CLASS:AlphaBeta");

        SwingUtilities.invokeAndWait(() -> result.panel.searchForTesting().setText("alpha"));
        assertThat(result.panel.stableTreeKeysForTesting()).doesNotContain(
                "entity:L:CLASS:Alpha", "entity:L:CLASS:AlphaBeta");
        controller.close();
    }

    @Test
    void summaryCountsOnlyClassLevelMatchesCandidatesAndChanges() throws Exception {
        EntityId oldClass = EntityId.classId("old/Owner");
        EntityId newClass = EntityId.classId("new/Owner");
        ScoreBreakdown score = new ScoreBreakdown(1.0, Map.of("fingerprint", 1.0));
        List<MatchDecision> confirmed = new java.util.ArrayList<>();
        confirmed.add(new MatchDecision(oldClass, newClass, MatchStatus.EXACT, score));
        Map<EntityId, List<MatchDecision>> memberCandidates = new java.util.LinkedHashMap<>();
        for (int index = 0; index < 8; index++) {
            EntityId oldMethod = EntityId.methodId("old/Owner", "m" + index, "()V");
            EntityId newMethod = EntityId.methodId("new/Owner", "x" + index, "()V");
            confirmed.add(new MatchDecision(oldMethod, newMethod, MatchStatus.EXACT, score));
            EntityId pendingMethod = EntityId.methodId("old/Owner", "p" + index, "()V");
            memberCandidates.put(pendingMethod,
                    List.of(new MatchDecision(pendingMethod, newMethod, MatchStatus.SUGGESTED, score)));
        }
        DiffNode changedClass = new DiffNode("class", "owner", EntityKind.CLASS,
                oldClass, newClass, java.util.Set.of(ChangeKind.CODE));
        DiffNode changedResource = new DiffNode("resource", "changed.txt", EntityKind.RESOURCE,
                EntityId.resourceId("changed.txt"), EntityId.resourceId("changed.txt"),
                java.util.Set.of(ChangeKind.RESOURCE));
        WorkspaceController.Workspace workspace = new WorkspaceController.Workspace(
                snapshot("left.jar"), snapshot("right.jar"),
                new MatchResult(confirmed, memberCandidates, java.util.Set.of(), java.util.Set.of()),
                new DiffResult(List.of(changedClass, changedResource), Map.of()), Map.of(),
                List.of(), List.of(), null, "", ProjectUiState.empty());
        WorkspaceController controller = new WorkspaceController();
        AtomicPanel result = new AtomicPanel();

        SwingUtilities.invokeAndWait(() -> result.panel = new WorkspacePanel(controller, workspace, ignored -> { }));

        assertThat(result.panel.summaryValuesForTesting().subList(2, 5)).containsExactly(1L, 0L, 1L);
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
                .contains("package:*:classes:sample", "package:*:classes:sample/deep");
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

    @Test
    void groupsEntitiesByChangedUnmatchedAndUnchangedStatus() throws Exception {
        EntityId leftClass = EntityId.classId("old/Changed");
        EntityId rightClass = EntityId.classId("new/Changed");
        DiffNode changed = new DiffNode("class:changed", "changed", EntityKind.CLASS,
                leftClass, rightClass, java.util.Set.of(ChangeKind.STRUCTURE));
        DiffNode unmatchedMethod = new DiffNode("method:unmatched", "old/Changed.missing()V",
                EntityKind.METHOD, EntityId.methodId("old/Changed", "missing", "()V"), null,
                java.util.Set.of(ChangeKind.REMOVED));
        DiffNode unchanged = new DiffNode("resource:stable.txt", "stable.txt", EntityKind.RESOURCE,
                EntityId.resourceId("stable.txt"), EntityId.resourceId("stable.txt"), java.util.Set.of());
        WorkspaceController.Workspace workspace = new WorkspaceController.Workspace(
                snapshot("left.jar"), snapshot("right.jar"),
                new MatchResult(List.of(), Map.of(), java.util.Set.of(), java.util.Set.of()),
                new DiffResult(List.of(changed, unmatchedMethod, unchanged), Map.of()),
                Map.of(), List.of(), List.of(), null, "", ProjectUiState.empty());
        WorkspaceController controller = new WorkspaceController();
        AtomicPanel result = new AtomicPanel();

        SwingUtilities.invokeAndWait(() -> result.panel = new WorkspacePanel(controller, workspace, ignored -> { }));

        assertThat(result.panel.rootKeysForTesting()).containsExactly(
                "status:changed", "status:unchanged");
        assertThat(result.panel.stableTreeKeysForTesting())
                .contains("status:changed:classes", "status:unchanged:resources")
                .doesNotContain("status:unmatched:members");
        assertThat(result.panel.stableTreeKeysForTesting().stream()
                .filter(key -> key.equals("entity:L:CLASS:old/Changed")))
                .hasSize(1);
        SwingUtilities.invokeAndWait(() -> result.panel.expandEntityForTesting("entity:L:CLASS:old/Changed"));
        assertThat(result.panel.stableTreeKeysForTesting())
                .contains("entity:L:METHOD:old/Changed.missing()V");
        controller.close();
    }

    @Test
    void treatsPureRenamesAsUnchangedAndAppliesManualClassification() throws Exception {
        EntityId leftClass = EntityId.classId("old/Owner");
        EntityId rightClass = EntityId.classId("new/Owner");
        EntityId leftMethod = EntityId.methodId("old/Owner", "read", "()I");
        EntityId rightMethod = EntityId.methodId("new/Owner", "a", "()I");
        DiffNode classNode = new DiffNode("class:rename", "old/Owner -> new/Owner", EntityKind.CLASS,
                leftClass, rightClass, java.util.Set.of(ChangeKind.RENAMED));
        DiffNode methodNode = new DiffNode("method:rename", "read -> a", EntityKind.METHOD,
                leftMethod, rightMethod, java.util.Set.of(ChangeKind.RENAMED));
        MatchDecision perfect = new MatchDecision(leftClass, rightClass, MatchStatus.EXACT,
                new ScoreBreakdown(1.0, Map.of("fingerprint", 1.0)));
        ComparisonOverrides overrides = new ComparisonOverrides(Map.of(), java.util.Set.of(), java.util.Set.of(),
                java.util.Set.of(), Map.of(new ClassPair(leftClass, rightClass),
                        ClassClassification.FORCE_CHANGED));
        WorkspaceController.Workspace workspace = new WorkspaceController.Workspace(
                snapshot("left.jar"), snapshot("right.jar"),
                new MatchResult(List.of(perfect), Map.of(), java.util.Set.of(), java.util.Set.of()),
                new DiffResult(List.of(classNode, methodNode), Map.of()), overrides,
                List.of(), List.of(), null, "", ProjectUiState.empty());
        WorkspaceController controller = new WorkspaceController();
        AtomicPanel result = new AtomicPanel();

        SwingUtilities.invokeAndWait(() -> result.panel = new WorkspacePanel(controller, workspace, ignored -> { }));

        assertThat(result.panel.rootKeysForTesting()).containsExactly("status:changed");
        assertThat(result.panel.treeLabelForKeyForTesting("entity:L:CLASS:old/Owner"))
                .doesNotContain("[", "Manual classification");
        assertThat(result.panel.treeTooltipForKeyForTesting("entity:L:CLASS:old/Owner"))
                .isEqualTo(org.earthsworth.wmatcher.app.I18n.text("tree.manualClassificationChanged"));

        javax.swing.tree.TreeModel treeModel = result.panel.treeForTesting().getModel();
        WorkspaceController.Workspace automatic = new WorkspaceController.Workspace(
                workspace.left(), workspace.right(), workspace.matches(), workspace.differences(),
                ComparisonOverrides.EMPTY, List.of(), List.of(), null, "", ProjectUiState.empty());
        SwingUtilities.invokeAndWait(() -> result.panel.refresh(automatic));
        assertThat(result.panel.rootKeysForTesting()).containsExactly("status:unchanged");
        assertThat(result.panel.treeForTesting().getModel()).isSameAs(treeModel);
        controller.close();
    }

    @Test
    void resolvesPopupTargetsAcrossTheWholeTreeRowOnly() throws Exception {
        DiffNode node = new DiffNode("class", "sample/Owner", EntityKind.CLASS,
                EntityId.classId("sample/Owner"), null, java.util.Set.of(ChangeKind.UNRESOLVED));
        WorkspaceController controller = new WorkspaceController();
        AtomicPanel result = new AtomicPanel();
        SwingUtilities.invokeAndWait(() -> {
            result.panel = new WorkspacePanel(controller, workspace(node), ignored -> { });
            result.panel.treeForTesting().setSize(700, 500);
            result.panel.treeForTesting().doLayout();
        });
        JTree tree = result.panel.treeForTesting();
        int row = Math.max(0, tree.getRowCount() - 1);
        java.awt.Rectangle bounds = tree.getRowBounds(row);

        assertThat(WorkspacePanel.popupPathAt(tree, 690, bounds.y + bounds.height / 2))
                .isEqualTo(tree.getPathForRow(row));
        assertThat(WorkspacePanel.popupPathAt(tree, 690, bounds.y + bounds.height + 20)).isNull();
        controller.close();
    }

    @Test
    void appliesManualClassificationWithoutReplacingTheTreeRoot() throws Exception {
        EntityId leftClass = EntityId.classId("old/Owner");
        EntityId rightClass = EntityId.classId("new/Owner");
        DiffNode classNode = new DiffNode("class", "owner", EntityKind.CLASS,
                leftClass, rightClass, java.util.Set.of());
        MatchDecision decision = new MatchDecision(leftClass, rightClass, MatchStatus.EXACT,
                new ScoreBreakdown(1.0, Map.of("fingerprint", 1.0)));
        WorkspaceController.Workspace automatic = new WorkspaceController.Workspace(
                snapshot("left.jar"), snapshot("right.jar"),
                new MatchResult(List.of(decision), Map.of(), java.util.Set.of(), java.util.Set.of()),
                new DiffResult(List.of(classNode), Map.of()), ComparisonOverrides.EMPTY,
                List.of(), List.of(), null, "", ProjectUiState.empty());
        ComparisonOverrides overrides = new ComparisonOverrides(Map.of(), java.util.Set.of(), java.util.Set.of(),
                java.util.Set.of(), Map.of(new ClassPair(leftClass, rightClass),
                        ClassClassification.FORCE_CHANGED));
        WorkspaceController.Workspace classified = new WorkspaceController.Workspace(
                automatic.left(), automatic.right(), automatic.matches(), automatic.differences(), overrides,
                List.of(), List.of(), null, "", ProjectUiState.empty());
        WorkspaceController controller = new WorkspaceController();
        AtomicPanel result = new AtomicPanel();
        SwingUtilities.invokeAndWait(() -> result.panel = new WorkspacePanel(controller, automatic, ignored -> { }));
        Object root = result.panel.treeForTesting().getModel().getRoot();

        SwingUtilities.invokeAndWait(() -> result.panel.applyAnalysisUpdateForTesting(
                new WorkspaceController.AnalysisUpdate(classified, java.util.Set.of(leftClass, rightClass), false,
                        WorkspaceController.PhaseTimings.ZERO)));

        assertThat(result.panel.treeForTesting().getModel().getRoot()).isSameAs(root);
        assertThat(result.panel.rootKeysForTesting()).containsExactly("status:changed");
        assertThat(result.panel.treeTooltipForKeyForTesting("entity:L:CLASS:old/Owner"))
                .isEqualTo(org.earthsworth.wmatcher.app.I18n.text("tree.manualClassificationChanged"));
        controller.close();
    }

    @Test
    void groupsUnmatchedClassesAndResourcesByOldAndNewSide() throws Exception {
        DiffNode oldClass = new DiffNode("old", "old/Only", EntityKind.CLASS,
                EntityId.classId("old/Only"), null, java.util.Set.of(ChangeKind.UNRESOLVED));
        DiffNode newResource = new DiffNode("new", "new.txt", EntityKind.RESOURCE,
                null, EntityId.resourceId("new.txt"), java.util.Set.of(ChangeKind.ADDED));
        WorkspaceController.Workspace workspace = new WorkspaceController.Workspace(
                snapshot("left.jar"), snapshot("right.jar"),
                new MatchResult(List.of(), Map.of(), java.util.Set.of(oldClass.left()),
                        java.util.Set.of(newResource.right())),
                new DiffResult(List.of(oldClass, newResource), Map.of()), Map.of(), List.of(), List.of(), null, "",
                ProjectUiState.empty());
        WorkspaceController controller = new WorkspaceController();
        AtomicPanel result = new AtomicPanel();

        SwingUtilities.invokeAndWait(() -> result.panel = new WorkspacePanel(controller, workspace, ignored -> { }));

        assertThat(result.panel.stableTreeKeysForTesting()).contains(
                "status:unmatched:old", "status:unmatched:old:classes",
                "status:unmatched:new", "status:unmatched:new:resources");
        controller.close();
    }

    @Test
    void memberSelectionUsesItsOwningClassAsTheMappingSubject() throws Exception {
        EntityId leftClass = EntityId.classId("old/Owner");
        EntityId rightClass = EntityId.classId("new/Owner");
        DiffNode classNode = new DiffNode("class", "owner", EntityKind.CLASS,
                leftClass, rightClass, java.util.Set.of());
        DiffNode method = new DiffNode("method", "method", EntityKind.METHOD,
                EntityId.methodId("old/Owner", "work", "()V"),
                EntityId.methodId("new/Owner", "work", "()V"), java.util.Set.of(ChangeKind.CODE));
        WorkspaceController.Workspace workspace = new WorkspaceController.Workspace(
                snapshot("left.jar"), snapshot("right.jar"),
                new MatchResult(List.of(), Map.of(), java.util.Set.of(), java.util.Set.of()),
                new DiffResult(List.of(classNode, method), Map.of()), Map.of(), List.of(), List.of(), null, "",
                ProjectUiState.empty());
        WorkspaceController controller = new WorkspaceController();
        AtomicPanel result = new AtomicPanel();

        SwingUtilities.invokeAndWait(() -> result.panel = new WorkspacePanel(controller, workspace, ignored -> { }));

        assertThat(result.panel.mappingSubjectForTesting(method)).isEqualTo(leftClass);
        assertThat(result.panel.rootKeysForTesting()).containsExactly("status:unchanged");
        controller.close();
    }

    @Test
    void classLevelUnchangedStatusIsNotOverriddenByMemberResolutionNoise() throws Exception {
        EntityId leftClass = EntityId.classId("old/Stable");
        EntityId rightClass = EntityId.classId("new/Stable");
        DiffNode classNode = new DiffNode("class", "stable", EntityKind.CLASS,
                leftClass, rightClass, java.util.Set.of());
        DiffNode unresolved = new DiffNode("unresolved", "candidate", EntityKind.METHOD,
                EntityId.methodId("old/Stable", "a", "()V"), null,
                java.util.Set.of(ChangeKind.UNRESOLVED));
        DiffNode added = new DiffNode("added", "added", EntityKind.FIELD, null,
                EntityId.fieldId("new/Stable", "b", "I"), java.util.Set.of(ChangeKind.ADDED));
        DiffNode removed = new DiffNode("removed", "removed", EntityKind.FIELD,
                EntityId.fieldId("old/Stable", "c", "I"), null, java.util.Set.of(ChangeKind.REMOVED));
        WorkspaceController.Workspace workspace = new WorkspaceController.Workspace(
                snapshot("left.jar"), snapshot("right.jar"),
                new MatchResult(List.of(), Map.of(), java.util.Set.of(), java.util.Set.of()),
                new DiffResult(List.of(classNode, unresolved, added, removed), Map.of()),
                Map.of(), List.of(), List.of(), null, "", ProjectUiState.empty());
        WorkspaceController controller = new WorkspaceController();
        AtomicPanel result = new AtomicPanel();

        SwingUtilities.invokeAndWait(() -> result.panel = new WorkspacePanel(controller, workspace, ignored -> { }));

        assertThat(result.panel.rootKeysForTesting()).containsExactly("status:unchanged");
        controller.close();
    }

    @Test
    void placesConfirmedSingleSidedEntitiesInChangedInsteadOfUnmatched() throws Exception {
        EntityId addedId = EntityId.resourceId("added.txt");
        DiffNode added = new DiffNode("resource:R:added.txt", "added.txt", EntityKind.RESOURCE,
                null, addedId, java.util.Set.of(ChangeKind.ADDED), ResolutionStatus.CONFIRMED_ADDED);
        WorkspaceController.Workspace workspace = new WorkspaceController.Workspace(
                snapshot("left.jar"), snapshot("right.jar"),
                new MatchResult(List.of(), Map.of(), java.util.Set.of(), java.util.Set.of(addedId)),
                new DiffResult(List.of(added), Map.of()), Map.of(), List.of(), List.of(), null, "",
                ProjectUiState.empty());
        WorkspaceController controller = new WorkspaceController();
        AtomicPanel result = new AtomicPanel();

        SwingUtilities.invokeAndWait(() -> result.panel = new WorkspacePanel(controller, workspace, ignored -> { }));

        assertThat(result.panel.rootKeysForTesting()).containsExactly("status:changed");
        controller.close();
    }

    @Test
    void sendsMembersFromNonDetailTabsToSourceAndPreservesCurrentDetailTab() {
        assertThat(WorkspacePanel.detailTabForSelection(EntityKind.METHOD, 0)).isEqualTo(4);
        assertThat(WorkspacePanel.detailTabForSelection(EntityKind.FIELD, 1)).isEqualTo(4);
        assertThat(WorkspacePanel.detailTabForSelection(EntityKind.METHOD, 2)).isEqualTo(2);
        assertThat(WorkspacePanel.detailTabForSelection(EntityKind.FIELD, 3)).isEqualTo(3);
        assertThat(WorkspacePanel.detailTabForSelection(EntityKind.CLASS, 0)).isZero();
    }

    @Test
    void migratesLegacyExpandedTreeKeysToStatusAwareKeys() throws Exception {
        EntityId leftId = EntityId.classId("sample/deep/Legacy");
        DiffNode node = new DiffNode("class:legacy", "sample/deep/Legacy", EntityKind.CLASS,
                leftId, null, java.util.Set.of(ChangeKind.UNRESOLVED));
        ProjectUiState legacyState = new ProjectUiState("", java.util.Set.of(), "",
                java.util.Set.of("root:classes", "package:classes:sample", "package:classes:sample/deep"),
                0, 340);
        WorkspaceController.Workspace workspace = new WorkspaceController.Workspace(
                snapshot("left.jar"), snapshot("right.jar"),
                new MatchResult(List.of(), Map.of(), java.util.Set.of(leftId), java.util.Set.of()),
                new DiffResult(List.of(node), Map.of()), Map.of(), List.of(), List.of(), null, "", legacyState);
        WorkspaceController controller = new WorkspaceController();
        AtomicPanel result = new AtomicPanel();

        SwingUtilities.invokeAndWait(() -> result.panel = new WorkspacePanel(controller, workspace, ignored -> { }));

        assertThat(result.panel.uiState().expandedTreeKeys())
                .contains("status:changed:classes", "status:unmatched:old:classes",
                        "status:unmatched:new:classes", "status:unchanged:classes",
                        "package:*:classes:sample", "package:*:classes:sample/deep");
        controller.close();
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
