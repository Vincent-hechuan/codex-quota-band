use codex_quota_windows_core::{
    AlertContext, AlertDelivery, AlertUrgency, NotificationMode, NotificationPolicy, TaskState,
};

#[test]
fn default_policy_only_alerts_after_chatgpt_loses_focus() {
    let policy = NotificationPolicy::default();

    assert_eq!(
        policy.plan(
            TaskState::WaitingForReview,
            AlertContext {
                chatgpt_focused: true,
                android_foreground: false,
                reconnect: false,
            },
        ),
        AlertDelivery::none()
    );
    assert_eq!(
        policy.plan(
            TaskState::Running,
            AlertContext {
                chatgpt_focused: false,
                android_foreground: false,
                reconnect: false,
            },
        ),
        AlertDelivery::none()
    );
    assert_eq!(
        policy.plan(
            TaskState::WaitingForReview,
            AlertContext {
                chatgpt_focused: false,
                android_foreground: false,
                reconnect: false,
            },
        ),
        AlertDelivery {
            phone: true,
            band: true,
            urgency: Some(AlertUrgency::Silent),
        }
    );
}

#[test]
fn android_foreground_suppresses_even_always_mode() {
    let policy = NotificationPolicy::new(NotificationMode::Always, true, true, true, true);

    assert_eq!(
        policy.plan(
            TaskState::NeedsAuthorization,
            AlertContext {
                chatgpt_focused: false,
                android_foreground: true,
                reconnect: false,
            },
        ),
        AlertDelivery::none()
    );
}

#[test]
fn reconnect_only_alerts_for_unresolved_authorization() {
    let policy = NotificationPolicy::default();
    let reconnect = AlertContext {
        chatgpt_focused: false,
        android_foreground: false,
        reconnect: true,
    };

    assert_eq!(
        policy.plan(TaskState::WaitingForReview, reconnect),
        AlertDelivery::none()
    );
    assert_eq!(
        policy.plan(TaskState::NeedsAuthorization, reconnect),
        AlertDelivery {
            phone: true,
            band: true,
            urgency: Some(AlertUrgency::Vibrate),
        }
    );
}

#[test]
fn event_and_channel_switches_are_independent() {
    let policy = NotificationPolicy::new(NotificationMode::Always, false, true, false, true);
    let background = AlertContext {
        chatgpt_focused: true,
        android_foreground: false,
        reconnect: false,
    };

    assert_eq!(
        policy.plan(TaskState::WaitingForReview, background),
        AlertDelivery::none()
    );
    assert_eq!(
        policy.plan(TaskState::NeedsAuthorization, background),
        AlertDelivery {
            phone: false,
            band: true,
            urgency: Some(AlertUrgency::Vibrate),
        }
    );
}
