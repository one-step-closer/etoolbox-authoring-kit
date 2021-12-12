/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.exadel.aem.toolkit.plugin.handlers.placement;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import com.exadel.aem.toolkit.api.annotations.layouts.Place;
import com.exadel.aem.toolkit.api.handlers.Source;
import com.exadel.aem.toolkit.api.handlers.Target;
import com.exadel.aem.toolkit.plugin.adapters.AdaptationBase;
import com.exadel.aem.toolkit.plugin.adapters.PlaceSetting;
import com.exadel.aem.toolkit.plugin.handlers.HandlerChains;
import com.exadel.aem.toolkit.plugin.handlers.placement.registries.MembersRegistry;
import com.exadel.aem.toolkit.plugin.handlers.placement.registries.SectionsRegistry;
import com.exadel.aem.toolkit.plugin.handlers.placement.sections.Section;
import com.exadel.aem.toolkit.plugin.utils.DialogConstants;
import com.exadel.aem.toolkit.plugin.utils.NamingUtil;
import com.exadel.aem.toolkit.plugin.utils.ordering.OrderingUtil;

/**
 * Helper object for distributing class member-bound widgets into container sections, such as {@code Tab}s
 * or {@code AccordionPanel}s, that are defined in the processed Java class or any of its superclasses
 */
public class PlacementHelper {

    private Target container;

    private SectionsRegistry sections;
    private MembersRegistry members;

    /**
     * Default (instantiation-preventing) constructor
     */
    private PlacementHelper() {
    }

    /* ----------------------
       Public placement logic
       ---------------------- */

    /**
     * Attempts to place as many as possible available members to the sections of the container provided
     */
    public void doPlacement() {
        if (sections != null && !sections.getAvailable().isEmpty()) {
            doMultiSectionPlacement();
        } else {
            doSimplePlacement();
        }
    }

    /**
     * Called by {@link PlacementHelper#doPlacement()} to process multi-section installations
     */
    private void doMultiSectionPlacement() {
        Iterator<Section> sectionIterator = sections.getAvailable().iterator();
        int iterationStep = 0;
        while (sectionIterator.hasNext()) {
            final boolean isFirstSection = iterationStep++ == 0;
            Section currentSection = sectionIterator.next();
            List<Source> currentSectionMembers = new ArrayList<>(currentSection.getSources());
            List<Source> assignableSectionMembers = members.getAllAvailable().stream()
                .filter(member -> currentSection.canContain(member, isFirstSection))
                .collect(Collectors.toList());
            boolean needToSortAgain = !currentSectionMembers.isEmpty() && !assignableSectionMembers.isEmpty();
            currentSectionMembers.addAll(assignableSectionMembers);
            if (needToSortAgain) {
                currentSectionMembers = OrderingUtil.sortMembers(currentSectionMembers);
            }
            if (!sections.getIgnored().contains(currentSection.getTitle())) {
                Target itemsContainer = currentSection.createItemsContainer(container);
                doSimplePlacement(currentSectionMembers, itemsContainer);
            } else {
                currentSectionMembers.forEach(item -> members.checkOut(item));
            }
        }
    }

    /**
     * Appends provided {@link Source}s to the {@link Target} manifesting a container node
     * @param container {@link Target} manifesting a pre-defined widget container
     * @param sources   List of sources, such as members of a Java class
     */
    private void doSimplePlacement(List<Source> sources, Target container) {
        PlacementCollisionSolver.checkForCollisions(sources);
        PlacementCollisionSolver.resolveFieldMethodNameCoincidences(sources);
        Target itemsElement = container.getOrCreateTarget(DialogConstants.NN_ITEMS);

        for (Source source : sources) {
            Target newElement = itemsElement.getOrCreateTarget(NamingUtil.stripGetterPrefix(source.getName()));
            HandlerChains.forMember().accept(source, newElement);
            members.checkOut(source);
            if (((AdaptationBase<?>) source).hasAdaptation(PlaceSetting.class)
                && source.adaptTo(PlaceSetting.class).getMatchingTarget() != null) {
                Target formerTarget = source.adaptTo(PlaceSetting.class).getMatchingTarget();
                formerTarget.getParent().removeTarget(formerTarget.getName());
            }
        }
    }

    /**
     * Appends as many of the available member sources as possible to the container node specified for this helper
     * instance. This is a rendition of placement suitable for a single-section setup
     */
    private void doSimplePlacement() {
        PlacementCollisionSolver.checkForCollisions(members.getAvailable());
        PlacementCollisionSolver.resolveFieldMethodNameCoincidences(members.getAvailable());
        Target itemsElement = container.getOrCreateTarget(DialogConstants.NN_ITEMS);

        while (!members.getAvailable().isEmpty()) {
            Source source = members.getAvailable().get(0);
            Target newElement = itemsElement.getOrCreateTarget(NamingUtil.stripGetterPrefix(source.getName()));
            HandlerChains.forMember().accept(source, newElement);

            // For historic reasons, we allow members annotated with @Place("...") in a single-column buildup without
            // throwing a "section not found" exception. But we should not be done with any of these members before we make
            // sure they won't fit in any of the nested containers. Therefore, when doing exactly single-column placement,
            // we engage not a full-scale checkout of a @Place-annotated member, but rather a "soft checkout".
            // Such member is placed "temporarily". It remains available for secondary PlacementHelpers, e.g. for a helper
            // invoked from an in-dialog Tabs or Accordion container
            if (!source.tryAdaptTo(Place.class).isPresent()) {
                members.checkOut(source);
            }
            PlaceSetting placeSetting = source.adaptTo(PlaceSetting.class);
            if (!placeSetting.getValue().isEmpty()) {
                members.softCheckOut(source);
                placeSetting.setMatchingTarget(newElement);
            } else {
                members.checkOut(source);
            }
        }
    }

    /* ----------------
       Instance builder
       ----------------*/

    /**
     * Retrieves a builder object for creating a {@link PlacementHelper} instance and populating it with data
     * @return {@code PlacementHelper} builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Used for creating a {@link PlacementHelper} instance and populating it with data
     */
    public static class Builder {
        private Target container;

        private SectionsRegistry sections;
        private MembersRegistry members;

        private Builder() {
        }

        /**
         * Assigns the reference to a {@code Target} object
         * @param value {@code Target} instance representing a Granite UI dialog, tab, or accordion panel. This one can
         *              be modified during the execution by adding new child containers
         * @return This instance
         */
        public Builder container(Target value) {
            this.container = value;
            return this;
        }

        /**
         * Assigns the {@link SectionsRegistry} object containing of sections that the dialog members can be distributed
         * into
         * @param value Collection of {@link Section} objects that preserve data for rendering container sections
         * @return This instance
         */
        public Builder sections(SectionsRegistry value) {
            this.sections = value;
            return this;
        }

        /**
         * Assigns the {@link MembersRegistry} object containing placeable members
         * @param value Registry of {@link Source} objects that represent placeable class members
         * @return This instance
         */
        public Builder members(MembersRegistry value) {
            this.members = value;
            return this;
        }

        /**
         * Creates the {@code PlacementHelper} object
         * @return {@link PlacementHelper} instance
         */
        public PlacementHelper build() {
            PlacementHelper result = new PlacementHelper();
            result.container = container;
            result.sections = sections;
            result.members = members;
            return result;
        }
    }
}
