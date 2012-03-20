/*
 * Copyright (C) 2011 Steven Luo
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

package com.futesat.sshterm;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

public class SSHTermActivity extends Activity 
{	 
	private SharedPreferences mPrefs;
	private TermSettings mSettings;
	private TermSession mSession;
	
	@Override
	public void onCreate(Bundle savedInstanceState)  
	{
		mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
		mSettings = new TermSettings(getResources(), mPrefs);

		super.onCreate(savedInstanceState);
		
		try 
		{
			mSession = createTermSession();
			setContentView(createEmulatorView(mSession));
		} 
		catch (Exception e) 
		{
			Log.e(SSHTermActivity.class.getName(), "session create error", e);
		}

		mHaveFullHwKeyboard = checkHaveFullHwKeyboard(getResources().getConfiguration());
	}

	private TermSession createTermSession() throws Exception 
	{
		String ip = getIntent().getStringExtra("ip");
		int port = getIntent().getIntExtra("port", 22);
		String user = getIntent().getStringExtra("user");
		String passwd = getIntent().getStringExtra("passwd");
		
		return new TermSession(mSettings, ip, port, user, passwd);
	}

	private EmulatorView createEmulatorView(TermSession session) 
	{
		DisplayMetrics metrics = new DisplayMetrics();
		getWindowManager().getDefaultDisplay().getMetrics(metrics);
		EmulatorView emulatorView = new EmulatorView(this, session, metrics);

		FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.WRAP_CONTENT,
				FrameLayout.LayoutParams.WRAP_CONTENT,
				Gravity.LEFT
				);

		emulatorView.setLayoutParams(params);

		emulatorView.setExtGestureListener(new EmulatorViewGestureListener(emulatorView));
		emulatorView.updatePrefs(mSettings);
		registerForContextMenu(emulatorView);

		return emulatorView;
	}

	private class EmulatorViewGestureListener extends SimpleOnGestureListener
	{
		private EmulatorView view;

		public EmulatorViewGestureListener(EmulatorView view) 
		{
			this.view = view;
		}

		@Override
		public boolean onSingleTapUp(MotionEvent e) 
		{
			doUIToggle((int) e.getX(), (int) e.getY(), view.getVisibleWidth(), view.getVisibleHeight());
			return true;
		}

		@Override
		public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY)
		{
			return false;
		}
	}
	
	@Override
	protected void onDestroy() 
	{
		super.onDestroy();
		mSession.finish();
	}

	private void doToggleSoftKeyboard() 
	{
		InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
		imm.toggleSoftInput(InputMethodManager.SHOW_FORCED,0);
	}

	private boolean mHaveFullHwKeyboard = false;

	private boolean checkHaveFullHwKeyboard(Configuration c) 
	{
		return (c.keyboard == Configuration.KEYBOARD_QWERTY) &&
				(c.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO);
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);

		mHaveFullHwKeyboard = checkHaveFullHwKeyboard(newConfig);
	}

	private int mActionBarMode = TermSettings.ACTION_BAR_MODE_NONE; /*TODO actionBarMode*/

	private void doUIToggle(int x, int y, int width, int height) 
	{
		switch (mActionBarMode) 
		{
			case TermSettings.ACTION_BAR_MODE_NONE:
			{    
	
				if (AndroidCompat.SDK >= 11 && (mHaveFullHwKeyboard || y < height / 2)) 
				{
					openOptionsMenu();
				} 
				else 
				{
					doToggleSoftKeyboard();
				}
	
				break;
			}
			case TermSettings.ACTION_BAR_MODE_ALWAYS_VISIBLE:
			{    
				if (!mHaveFullHwKeyboard) 
				{
					doToggleSoftKeyboard();
				}
	
				break;
			}
			case TermSettings.ACTION_BAR_MODE_HIDES:
			{  
	
				if (mHaveFullHwKeyboard || y < height / 2) 
				{
					//TODO doToggleActionBar();
				} 
				else
				{
					doToggleSoftKeyboard();
				}
	
				break;
			}
		}
	}
}