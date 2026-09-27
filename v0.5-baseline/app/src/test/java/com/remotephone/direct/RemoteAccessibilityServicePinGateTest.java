package com.remotephone.direct;

import android.view.accessibility.AccessibilityNodeInfo;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RemoteAccessibilityServicePinGateTest {
    private static final String SYSTEM_UI = "com.android.systemui";

    @Test public void readyWithFullClickableKeypadAndNoEditableNode() {
        AccessibilityNodeInfo root = fullKeypad();
        assertTrue(RemoteAccessibilityService.isPinKeypadGateReadyForTest(true, root));
    }

    @Test public void notReadyWhenKeyguardUnlocked() {
        assertFalse(RemoteAccessibilityService.isPinKeypadGateReadyForTest(false, fullKeypad()));
    }

    @Test public void notReadyWhenRequiredDigitMissing() {
        AccessibilityNodeInfo root = fullKeypad();
        when(root.findAccessibilityNodeInfosByText("7")).thenReturn(Collections.emptyList());
        assertFalse(RemoteAccessibilityService.isPinKeypadGateReadyForTest(true, root));
    }

    @Test public void notReadyWhenRequiredDigitDisabled() {
        AccessibilityNodeInfo root = fullKeypad();
        AccessibilityNodeInfo digit = exactDigit(root, "4");
        when(digit.isEnabled()).thenReturn(false);
        assertFalse(RemoteAccessibilityService.isPinKeypadGateReadyForTest(true, root));
    }

    @Test public void notReadyWhenRequiredDigitNotClickable() {
        AccessibilityNodeInfo root = fullKeypad();
        AccessibilityNodeInfo digit = exactDigit(root, "9");
        when(digit.isClickable()).thenReturn(false);
        assertFalse(RemoteAccessibilityService.isPinKeypadGateReadyForTest(true, root));
    }

    private static AccessibilityNodeInfo fullKeypad() {
        AccessibilityNodeInfo root = mock(AccessibilityNodeInfo.class);
        AccessibilityNodeInfo keyguardContainer = mock(AccessibilityNodeInfo.class);
        when(root.getPackageName()).thenReturn(SYSTEM_UI);
        when(root.getChildCount()).thenReturn(1);
        when(root.getChild(0)).thenReturn(keyguardContainer);
        when(keyguardContainer.getViewIdResourceName())
                .thenReturn("com.android.systemui:id/keyguard_security_container");
        when(keyguardContainer.getChildCount()).thenReturn(0);

        for (char digit = '0'; digit <= '9'; digit++) {
            String label = String.valueOf(digit);
            AccessibilityNodeInfo key = mock(AccessibilityNodeInfo.class);
            when(key.getContentDescription()).thenReturn(label);
            when(key.isEnabled()).thenReturn(true);
            when(key.isClickable()).thenReturn(true);
            // Visibility false is intentionally allowed as advisory-only.
            when(key.isVisibleToUser()).thenReturn(false);
            when(root.findAccessibilityNodeInfosByText(label))
                    .thenReturn(Collections.singletonList(key));
        }
        return root;
    }

    private static AccessibilityNodeInfo exactDigit(AccessibilityNodeInfo root, String label) {
        return root.findAccessibilityNodeInfosByText(label).get(0);
    }
}
