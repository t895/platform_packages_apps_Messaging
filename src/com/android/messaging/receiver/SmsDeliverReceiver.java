/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.messaging.receiver;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.action.ReceiveSmsMessageAction;
import com.android.messaging.sms.MmsUtils;
import com.android.messaging.util.BugleGservices;
import com.android.messaging.util.BugleGservicesKeys;
import com.android.messaging.util.DebugUtils;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;

import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Class that receives incoming SMS messages.
 */
public final class SmsDeliverReceiver extends BroadcastReceiver {
    private static final String TAG = LogUtil.BUGLE_TAG;

    private static ArrayList<Pattern> sIgnoreSmsPatterns;

    private static final String EXTRA_ERROR_CODE = "errorCode";
    private static final String EXTRA_SUB_ID = "subscription";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(intent.getAction())) {
            deliverSmsIntent(context, intent);
        }
    }

    public static void deliverSmsIntent(final Context context, final Intent intent) {
        final android.telephony.SmsMessage[] messages = getMessagesFromIntent(intent);

        // Check messages for validity
        if (messages == null || messages.length < 1) {
            LogUtil.e(TAG, "processReceivedSms: null or zero or ignored message");
            return;
        }

        final int errorCode =
                intent.getIntExtra(EXTRA_ERROR_CODE, SendStatusReceiver.NO_ERROR_CODE);
        // Always convert negative subIds into -1
        int subId = PhoneUtils.getDefault().getEffectiveIncomingSubIdFromSystem(
                intent, EXTRA_SUB_ID);
        deliverSmsMessages(context, subId, errorCode, messages);
        if (MmsUtils.isDumpSmsEnabled()) {
            final String format = intent.getStringExtra("format");
            DebugUtils.dumpSms(messages[0].getTimestampMillis(), messages, format);
        }
    }

    public static void deliverSmsMessages(final Context context, final int subId,
                                          final int errorCode, final android.telephony.SmsMessage[] messages) {
        final ContentValues messageValues =
                MmsUtils.parseReceivedSmsMessage(context, messages, errorCode);

        LogUtil.v(TAG, "SmsReceiver.deliverSmsMessages");

        final long nowInMillis =  System.currentTimeMillis();
        final long receivedTimestampMs = MmsUtils.getMessageDate(messages[0], nowInMillis);

        messageValues.put(Telephony.Sms.Inbox.DATE, receivedTimestampMs);
        // Default to unread and unseen for us but ReceiveSmsMessageAction will override
        // seen for the telephony db.
        messageValues.put(Telephony.Sms.Inbox.READ, 0);
        messageValues.put(Telephony.Sms.Inbox.SEEN, 0);
        messageValues.put(Telephony.Sms.SUBSCRIPTION_ID, subId);

        if (messages[0].getMessageClass() == android.telephony.SmsMessage.MessageClass.CLASS_0 ||
                DebugUtils.debugClassZeroSmsEnabled()) {
            Factory.get().getUIIntents().launchClassZeroActivity(context, messageValues);
        } else {
            final ReceiveSmsMessageAction action = new ReceiveSmsMessageAction(messageValues);
            action.start();
        }
    }

    /**
     * Get the SMS messages from the specified SMS intent.
     * @return the messages. If there is an error or the message should be ignored, return null.
     */
    public static android.telephony.SmsMessage[] getMessagesFromIntent(Intent intent) {
        final android.telephony.SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);

        // Check messages for validity
        if (messages == null || messages.length < 1) {
            return null;
        }
        // Sometimes, SmsMessage.mWrappedSmsMessage is null causing NPE when we access
        // the methods on it although the SmsMessage itself is not null. So do this check
        // before we do anything on the parsed SmsMessages.
        try {
            final String messageBody = messages[0].getDisplayMessageBody();
            if (messageBody != null) {
                // Compile patterns if necessary
                if (sIgnoreSmsPatterns == null) {
                    compileIgnoreSmsPatterns();
                }
                // Check against filters
                for (final Pattern pattern : sIgnoreSmsPatterns) {
                    if (pattern.matcher(messageBody).matches()) {
                        return null;
                    }
                }
            }
        } catch (final NullPointerException e) {
            LogUtil.e(TAG, "shouldIgnoreMessage: NPE inside SmsMessage");
            return null;
        }
        return messages;
    }

    /**
     * Compile all of the patterns we check for to ignore system SMS messages.
     */
    private static void compileIgnoreSmsPatterns() {
        // Get the pattern set from GServices
        final String smsIgnoreRegex = BugleGservices.get().getString(
                BugleGservicesKeys.SMS_IGNORE_MESSAGE_REGEX,
                BugleGservicesKeys.SMS_IGNORE_MESSAGE_REGEX_DEFAULT);
        if (smsIgnoreRegex != null) {
            final String[] ignoreSmsExpressions = smsIgnoreRegex.split("\n");
            if (ignoreSmsExpressions.length != 0) {
                sIgnoreSmsPatterns = new ArrayList<Pattern>();
                for (int i = 0; i < ignoreSmsExpressions.length; i++) {
                    try {
                        sIgnoreSmsPatterns.add(Pattern.compile(ignoreSmsExpressions[i]));
                    } catch (PatternSyntaxException e) {
                        LogUtil.e(TAG, "compileIgnoreSmsPatterns: Skipping bad expression: " +
                                ignoreSmsExpressions[i]);
                    }
                }
            }
        }
    }
}
