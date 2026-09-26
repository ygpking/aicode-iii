package com.aicode.feature.agent.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTypeDetectorTest {

    @Test
    fun detectsAndroidGradle() {
        assertEquals(
            listOf(ProjectType.ANDROID_GRADLE),
            ProjectTypeDetector.detect(listOf("settings.gradle.kts", "app", "gradle")),
        )
        assertEquals(
            listOf(ProjectType.ANDROID_GRADLE),
            ProjectTypeDetector.detect(listOf("build.gradle")),
        )
    }

    @Test
    fun detectsFlutterNodeRustGoPython() {
        assertEquals(listOf(ProjectType.FLUTTER), ProjectTypeDetector.detect(listOf("pubspec.yaml", "lib")))
        assertEquals(listOf(ProjectType.NODE), ProjectTypeDetector.detect(listOf("package.json", "src")))
        assertEquals(listOf(ProjectType.RUST), ProjectTypeDetector.detect(listOf("Cargo.toml", "src")))
        assertEquals(listOf(ProjectType.GO), ProjectTypeDetector.detect(listOf("go.mod", "main.go")))
        assertEquals(listOf(ProjectType.PYTHON), ProjectTypeDetector.detect(listOf("pyproject.toml")))
        assertEquals(listOf(ProjectType.PYTHON), ProjectTypeDetector.detect(listOf("requirements.txt")))
    }

    @Test
    fun unknownProjectYieldsEmpty() {
        assertTrue(ProjectTypeDetector.detect(listOf("README.md", "docs")).isEmpty())
        assertTrue(ProjectTypeDetector.detect(emptyList()).isEmpty())
    }

    @Test
    fun multipleMarkersReturnAllInStableOrder() {
        val types = ProjectTypeDetector.detect(listOf("settings.gradle", "package.json", "Cargo.toml"))
        assertEquals(listOf(ProjectType.ANDROID_GRADLE, ProjectType.NODE, ProjectType.RUST), types)
    }

    @Test
    fun guidanceIsNonEmptyForEveryType() {
        ProjectType.values().forEach { type ->
            assertTrue("类型 $type 的引导不应为空", ProjectTypeDetector.guidance(type).isNotBlank())
        }
    }
}
