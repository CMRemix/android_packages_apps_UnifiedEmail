/*
 * Copyright (C) 2012 Google Inc.
 * Licensed to The Android Open Source Project.
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

package com.android.mail.browse;

import android.content.Loader;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebSettings.LayoutAlgorithm;
import android.webkit.WebView;

import com.android.mail.R;
import com.android.mail.browse.ConversationViewAdapter.MessageHeaderItem;
import com.android.mail.browse.MessageCursor.ConversationMessage;
import com.android.mail.browse.MessageHeaderView.MessageHeaderViewCallbacks;
import com.android.mail.providers.Conversation;
import com.android.mail.providers.ConversationInfo;
import com.android.mail.providers.Message;
import com.android.mail.providers.UIProvider;
import com.android.mail.ui.AbstractConversationViewFragment;
import com.android.mail.ui.ControllableActivity;
import com.android.mail.ui.ConversationViewFragment;
import com.android.mail.ui.SubjectDisplayChanger;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;

import java.util.HashSet;

public class SecureConversationViewFragment extends AbstractConversationViewFragment implements
        MessageHeaderViewCallbacks {
    private static final String LOG_TAG = LogTag.getLogTag();
    private WebView mWebView;
    private ConversationViewHeader mConversationHeaderView;
    private MessageHeaderView mMessageHeaderView;
    private ConversationMessage mMessage;

    /**
     * Creates a new instance of {@link ConversationViewFragment}, initialized
     * to display a conversation with other parameters inherited/copied from an
     * existing bundle, typically one created using {@link #makeBasicArgs}.
     */
    public static SecureConversationViewFragment newInstance(Bundle existingArgs,
            Conversation conversation) {
        SecureConversationViewFragment f = new SecureConversationViewFragment();
        Bundle args = new Bundle(existingArgs);
        args.putParcelable(ARG_CONVERSATION, conversation);
        f.setArguments(args);
        return f;
    }

    /**
     * Constructor needs to be public to handle orientation changes and activity
     * lifecycle events.
     */
    public SecureConversationViewFragment() {
        super();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mConversationHeaderView.setCallbacks(this, this);
        mConversationHeaderView.setSubject(mConversation.subject, false /* notify */);
        if (mAccount.supportsCapability(UIProvider.AccountCapabilities.MULTIPLE_FOLDERS_PER_CONV)) {
            mConversationHeaderView.setFolders(mConversation, false /* notify */);
        }
        final SubjectDisplayChanger sdc = mActivity.getSubjectDisplayChanger();
        if (sdc != null) {
            sdc.setSubject(mConversation.subject);
        }
        mMessageHeaderView.setContactInfoSource(getContactInfoSource());
        mMessageHeaderView.setCallbacks(this);
        mMessageHeaderView.setExpandable(false);
        getLoaderManager().initLoader(MESSAGE_LOADER, null, getMessageLoaderCallbacks());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.secure_conversation_view, container, false);
        mConversationHeaderView = (ConversationViewHeader) rootView.findViewById(R.id.conv_header);
        mMessageHeaderView = (MessageHeaderView) rootView.findViewById(R.id.message_header);
        mWebView = (WebView) rootView.findViewById(R.id.webview);
        final WebSettings settings = mWebView.getSettings();

        settings.setJavaScriptEnabled(false);
        settings.setLayoutAlgorithm(LayoutAlgorithm.NORMAL);

        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        return rootView;
    }

    @Override
    protected void markUnread() {
        // Ignore unsafe calls made after a fragment is detached from an
        // activity
        final ControllableActivity activity = (ControllableActivity) getActivity();
        if (activity == null) {
            LogUtils.w(LOG_TAG, "ignoring markUnread for conv=%s", mConversation.id);
            return;
        }
        HashSet<Uri> uris = new HashSet<Uri>();
        uris.add(mMessage.uri);
        activity.getConversationUpdater().markConversationMessagesUnread(mConversation, uris,
                ConversationInfo.toString(mConversation.conversationInfo));
    }

    @Override
    public void onAccountChanged() {
        // Do nothing.
    }

    @Override
    public void setMessageSpacerHeight(MessageHeaderItem item, int newSpacerHeight) {
        // Do nothing.
    }

    @Override
    public void setMessageExpanded(MessageHeaderItem item, int newSpacerHeight) {
        // Do nothing.
    }

    @Override
    public void onConversationViewHeaderHeightChange(int newHeight) {
        // Do nothing.
    }

    @Override
    public void onUserVisibleHintChanged() {
        // Do nothing.
    }

    @Override
    public void showExternalResources(Message msg) {
        mWebView.getSettings().setBlockNetworkImage(false);
    }

    @Override
    protected void onMessageCursorLoadFinished(Loader<Cursor> loader, Cursor data,
            boolean wasNull, boolean changed) {
        MessageCursor messageCursor = getMessageCursor();

        // ignore cursors that are still loading results
        if (messageCursor == null || !messageCursor.isLoaded()) {
            LogUtils.i(LOG_TAG, "CONV RENDER: existing cursor is null, rendering from scratch");
            return;
        }
        renderMessageBodies(messageCursor, mEnableContentReadySignal);
    }

    /**
     * Populate the adapter with overlay views (message headers, super-collapsed
     * blocks, a conversation header), and return an HTML document with spacer
     * divs inserted for all overlays.
     */
    private void renderMessageBodies(MessageCursor messageCursor,
            boolean enableContentReadySignal) {
        StringBuilder convHtml = new StringBuilder();
        String content;
        if (messageCursor.moveToFirst()) {
            content = messageCursor.getString(UIProvider.MESSAGE_BODY_HTML_COLUMN);
            if (TextUtils.isEmpty(content)) {
                content = messageCursor.getString(UIProvider.MESSAGE_BODY_TEXT_COLUMN);
            }
            convHtml.append(content);
            mWebView.loadDataWithBaseURL(mBaseUri, convHtml.toString(), "text/html", "utf-8", null);
            mMessage = messageCursor.getMessage();
            ConversationViewAdapter mAdapter = new ConversationViewAdapter(mActivity, null, null,
                    null, null, null, null, null, null);
            MessageHeaderItem item = mAdapter.newMessageHeaderItem(mMessage, true);
            mMessageHeaderView.initialize(mDateBuilder, this, mAddressCache);
            mMessageHeaderView.bind(item, false);
            mMessageHeaderView.setMessageDetailsVisibility(View.VISIBLE);
        }
    }
}