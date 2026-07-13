package com.stonewu.fusion.service.script;

import com.stonewu.fusion.service.script.model.SceneEntity;
import com.stonewu.fusion.service.script.model.SceneEntityManifest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SceneEntityManifestServiceTests {

    @Test
    void manifestRoundTripKeepsCollectiveAndResolvedIds() {
        SceneEntity entity = new SceneEntity("character:evacuees", "撤离士兵群",
                "character", "collective", "core", true, 11L, 21L, "auto_created");

        SceneEntityManifest parsed = SceneEntityManifest.fromJson(
                new SceneEntityManifest(1, List.of(entity)).toJson());

        assertThat(parsed.entities()).containsExactly(entity);
    }

    @Test
    void blankJsonReturnsInitialEmptyManifest() {
        SceneEntityManifest manifest = SceneEntityManifest.fromJson("  ");

        assertThat(manifest.version()).isEqualTo(1);
        assertThat(manifest.entities()).isEmpty();
    }
}
