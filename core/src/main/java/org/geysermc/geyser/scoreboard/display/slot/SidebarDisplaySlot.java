/*
 * Copyright (c) 2024 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.scoreboard.display.slot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.cloudburstmc.protocol.bedrock.data.ScoreInfo;
import org.geysermc.geyser.entity.type.player.PlayerEntity;
import org.geysermc.geyser.scoreboard.Objective;
import org.geysermc.geyser.scoreboard.ScoreReference;
import org.geysermc.geyser.scoreboard.Team;
import org.geysermc.geyser.scoreboard.UpdateType;
import org.geysermc.geyser.scoreboard.display.score.SidebarDisplayScore;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.text.ChatColor;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ScoreboardPosition;

public final class SidebarDisplaySlot extends DisplaySlot {
    private static final int SCORE_DISPLAY_LIMIT = 15;
    private static final Comparator<ScoreReference> SCORE_DISPLAY_ORDER =
        Comparator.comparing(ScoreReference::score)
            .reversed()
            .thenComparing(ScoreReference::name, String.CASE_INSENSITIVE_ORDER);

    private List<SidebarDisplayScore> displayScores = new ArrayList<>(SCORE_DISPLAY_LIMIT);
    /// A copy of displayScores which can be modified by the render0 method for its calculation of the scores to
    /// display. This was done to not add locks to displayScores, as that list can be used by multiple threads because
    /// of the setTeamFor method. Additionally, there is a brief period in render0 where scores are not present in the
    /// list which could lead to bugs that are hard to reproduce.
    private final List<SidebarDisplayScore> displayScoresCopy = new ArrayList<>(SCORE_DISPLAY_LIMIT);

    public SidebarDisplaySlot(GeyserSession session, Objective objective, ScoreboardPosition position) {
        super(session, objective, position);
    }

    @Override
    protected void render0(List<ScoreInfo> addScores, List<ScoreInfo> removeScores) {
        // While one could argue that we may not have to do this fancy Java filter when there are fewer scores than the
        // line limit, it is also responsible for making sure that the scores are in the correct order.
        var newDisplayScores =
            objective.getScores().values().stream()
                .filter(score -> !score.hidden())
                .sorted(SCORE_DISPLAY_ORDER)
                .limit(SCORE_DISPLAY_LIMIT)
                .map(reference -> {
                    // pretty much an ArrayList#remove
                    var iterator = displayScoresCopy.iterator();
                    while (iterator.hasNext()) {
                        var score = iterator.next();
                        if (score.name().equals(reference.name())) {
                            iterator.remove();
                            return score;
                        }
                    }

                    // new score, so it should be added
                    return new SidebarDisplayScore(this, objective.getScoreboard().nextId(), reference);
                }).collect(Collectors.toList());

        // Make sure that we set the displayScores as early as possible, because setTeamFor relies on these potential
        // changes. And even if no scores were added or removed, the order could've changed.
        displayScores = newDisplayScores;

        // In newDisplayScores we removed the items that were already present from displayScoresCopy,
        // meaning that the items that remain are items that are no longer displayed.
        for (var score : displayScoresCopy) {
            removeScores.add(score.cachedInfo());
        }

        // The newDisplayScores have to be copied over to displayScoresCopy for the next render.
        for (int i = 0; i < newDisplayScores.size(); i++) {
            if (i < displayScoresCopy.size()) {
                displayScoresCopy.set(i, newDisplayScores.get(i));
            } else {
                displayScoresCopy.add(newDisplayScores.get(i));
            }
        }

        // fixes ordering issues with multiple entries with same score
        if (!displayScores.isEmpty()) {
            SidebarDisplayScore lastScore = null;
            int count = 0;
            for (var score : displayScores) {
                if (lastScore == null) {
                    lastScore = score;
                    continue;
                }

                if (score.score() == lastScore.score()) {
                    // Bedrock doesn't support some legacy color codes and adds some codes as well.
                    // Keep this in mind if the line limit is ever increased.
                    if (count == 0) {
                        lastScore.order(ChatColor.colorDisplayOrder(count++));
                    }
                    score.order(ChatColor.colorDisplayOrder(count++));
                } else {
                    if (count == 0) {
                        lastScore.order(null);
                    }
                    count = 0;
                }
                lastScore = score;
            }

            if (count == 0 && lastScore != null) {
                lastScore.order(null);
            }
        }

        boolean objectiveAdd = updateType == UpdateType.ADD;
        boolean objectiveUpdate = updateType == UpdateType.UPDATE;

        for (var score : displayScores) {
            Team team = score.team();
            boolean add = objectiveAdd || objectiveUpdate;
            boolean exists = score.exists();

            if (team != null) {
                // entities are mostly removed from teams without notifying the scores.
                if (team.shouldRemove() || !team.hasEntity(score.name())) {
                    score.team(null);
                    add = true;
                }
            }

            if (score.shouldUpdate()) {
                score.update(objective);
                add = true;
            }

            if (add) {
                addScores.add(score.cachedInfo());
            }

            // we need this as long as MCPE-143063 hasn't been fixed.
            // the checks after 'add' are there to prevent removing scores that
            // are going to be removed anyway / don't need to be removed
            if (add && exists && !(objectiveUpdate || objectiveAdd) && !score.onlyScoreValueChanged()) {
                removeScores.add(score.cachedInfo());
            }
        }

        if (objectiveUpdate) {
            sendRemoveObjective();
        }

        if (objectiveAdd || objectiveUpdate) {
            sendDisplayObjective();
        }

        updateType = UpdateType.NOTHING;
    }

    @Override
    public void addScore(ScoreReference reference) {
        // we handle them a bit different: we sort the scores, and we add them ourselves
    }

    @Override
    public void playerRegistered(PlayerEntity player) {

    }

    @Override
    public void playerRemoved(PlayerEntity player) {

    }

    public void setTeamFor(Team team, Set<String> entities) {
        // we only have to worry about scores that are currently displayed,
        // because the constructor of the display score fetches the team
        for (var score : displayScores) {
            if (entities.contains(score.name())) {
                score.team(team);
            }
        }
    }
}
