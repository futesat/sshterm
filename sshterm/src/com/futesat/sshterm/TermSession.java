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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.EnumMap;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.PTYMode;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Session.Shell;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;

import android.os.Handler;
import android.os.Message;
import android.util.Log;

/**
 * A terminal session, consisting of a TerminalEmulator, a TranscriptScreen, the
 * PID of the process attached to the session, and the I/O streams used to talk
 * to the process.
 */
public class TermSession {
	private SSHClient mSsh;
	private Session mSession;
	private Shell mShell;

	private TermSettings mSettings;
	private UpdateCallback mNotify;

	private OutputStream mTermOut;
	private InputStream mTermIn;

	private TranscriptScreen mTranscriptScreen;
	private TerminalEmulator mEmulator;

	private Thread mPollingThread;
	private ByteQueue mByteQueue;
	private byte[] mReceiveBuffer;

	private CharBuffer mWriteCharBuffer;
	private ByteBuffer mWriteByteBuffer;
	private CharsetEncoder mUTF8Encoder;


	// Number of rows in the transcript
	private static final int TRANSCRIPT_ROWS = 10000;

	private static final int NEW_INPUT = 1;

	private boolean mIsRunning = false;

	private Handler mMsgHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			if (!mIsRunning) {
				return;
			}
			if (msg.what == NEW_INPUT) {
				readFromProcess();
			} 
		}
	};

	public TermSession(TermSettings settings, String ip, int port, String user, String password) throws Exception 
	{
		mSettings = settings;
		mTermOut = null;
		mTermIn = null;

		try 
		{
			mSsh = new SSHClient();

			mSsh.addHostKeyVerifier(new HostKeyVerifier() 
			{
				public boolean verify(String arg0, int arg1, PublicKey arg2) {
					return true;
				}
			});

			mSsh.connect(ip, port);
			mSsh.authPassword(user, password);
			mSsh.setConnectTimeout(2000);

			mSession = mSsh.startSession();
			mSession.allocatePTY("ansi", 80, 24, 640, 480,
					new EnumMap<PTYMode, Integer>(PTYMode.class));

			mShell = mSession.startShell();
			mTermIn = mShell.getInputStream();
			mTermOut = mShell.getOutputStream();
		}
		catch (Exception e) 
		{
			/*
			 * UserAuthException,TransportException,ConnectionException,IOException
			 */
			Log.w(TermSession.class.getName(), "exec command error", e);
			this.cleanup();
			throw e;
		}

		mWriteCharBuffer = CharBuffer.allocate(2);
		mWriteByteBuffer = ByteBuffer.allocate(4);
		mUTF8Encoder = Charset.forName("UTF-8").newEncoder();
		mUTF8Encoder.onMalformedInput(CodingErrorAction.REPLACE);
		mUTF8Encoder.onUnmappableCharacter(CodingErrorAction.REPLACE);

		mReceiveBuffer = new byte[4 * 1024];
		mByteQueue = new ByteQueue(4 * 1024);

		mPollingThread = new Thread() {
			private byte[] mBuffer = new byte[4096];

			@Override
			public void run() 
			{
				try
				{
					while (!isInterrupted()) 
					{
						int read = mTermIn.read(mBuffer);
						
						if (read == -1) 
						{
							// EOF -- process exited
							return;
						}
						
						mByteQueue.write(mBuffer, 0, read);
						mMsgHandler.sendMessage(mMsgHandler.obtainMessage(NEW_INPUT));
					}
				} 
				catch (Exception e)
				{
					Log.w(TermSession.class.getName(), "polling thread error", e);
				} 
			}
		};

		mPollingThread.setName("Input reader");
	}

	private void initializeEmulator(int columns, int rows) {
		TermSettings settings = mSettings;
		int[] colorScheme = settings.getColorScheme();
		mTranscriptScreen = new TranscriptScreen(columns, TRANSCRIPT_ROWS,
				rows, colorScheme[0], colorScheme[2]);
		mEmulator = new TerminalEmulator(settings, mTranscriptScreen, columns,
				rows, mTermOut);

		mIsRunning = true;
		mPollingThread.start();
	}
	
	public void write(String data) {
		try {
			mTermOut.write(data.getBytes("UTF-8"));
			mTermOut.flush();
		} catch (IOException e) {
			// Ignore exception
			// We don't really care if the receiver isn't listening.
			// We just make a best effort to answer the query.
		}
	}

	public void write(int codePoint) {
		CharBuffer charBuf = mWriteCharBuffer;
		ByteBuffer byteBuf = mWriteByteBuffer;
		CharsetEncoder encoder = mUTF8Encoder;
		try {
			charBuf.clear();
			byteBuf.clear();
			Character.toChars(codePoint, charBuf.array(), 0);
			encoder.reset();
			encoder.encode(charBuf, byteBuf, true);
			encoder.flush(byteBuf);
			mTermOut.write(byteBuf.array(), 0, byteBuf.position() - 1);
			mTermOut.flush();
		} catch (IOException e) {
			// Ignore exception
		}
	}

	/*
	 * private void createSubprocess(int[] processId) { String shell =
	 * mSettings.getShell(); ArrayList<String> argList = parse(shell); String
	 * arg0 = argList.get(0); String[] args = argList.toArray(new String[1]);
	 * 
	 * String termType = mSettings.getTermType(); String[] env = new String[1];
	 * env[0] = "TERM=" + termType;
	 * 
	 * mTermFd = Exec.createSubprocess(arg0, args, env, processId); }
	 */

	private ArrayList<String> parse(String cmd) 
	{
		final int PLAIN = 0;
		final int WHITESPACE = 1;
		final int INQUOTE = 2;
		int state = WHITESPACE;
		ArrayList<String> result = new ArrayList<String>();
		int cmdLen = cmd.length();
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < cmdLen; i++) {
			char c = cmd.charAt(i);
			if (state == PLAIN) {
				if (Character.isWhitespace(c)) {
					result.add(builder.toString());
					builder.delete(0, builder.length());
					state = WHITESPACE;
				} else if (c == '"') {
					state = INQUOTE;
				} else {
					builder.append(c);
				}
			} else if (state == WHITESPACE) {
				if (Character.isWhitespace(c)) {
					// do nothing
				} else if (c == '"') {
					state = INQUOTE;
				} else {
					state = PLAIN;
					builder.append(c);
				}
			} else if (state == INQUOTE) {
				if (c == '\\') {
					if (i + 1 < cmdLen) {
						i += 1;
						builder.append(cmd.charAt(i));
					}
				} else if (c == '"') {
					state = PLAIN;
				} else {
					builder.append(c);
				}
			}
		}
		if (builder.length() > 0) {
			result.add(builder.toString());
		}
		return result;
	}

	public OutputStream getTermOut() {
		return mTermOut;
	}

	public TranscriptScreen getTranscriptScreen() {
		return mTranscriptScreen;
	}

	public TerminalEmulator getEmulator() {
		return mEmulator;
	}

	public void setUpdateCallback(UpdateCallback notify) {
		mNotify = notify;
	}

	public void updateSize(int columns, int rows) {
		// Inform the attached pty of our new size:
		// Exec.setPtyWindowSize(mTermFd, rows, columns, 0, 0);

		if (mEmulator == null) {
			initializeEmulator(columns, rows);
		} else {
			mEmulator.updateSize(columns, rows);
		}
	}

	public String getTranscriptText() {
		return mTranscriptScreen.getTranscriptText();
	}

	/**
	 * Look for new input from the ptty, send it to the terminal emulator.
	 */
	private void readFromProcess() {
		int bytesAvailable = mByteQueue.getBytesAvailable();
		int bytesToRead = Math.min(bytesAvailable, mReceiveBuffer.length);
		try {
			int bytesRead = mByteQueue.read(mReceiveBuffer, 0, bytesToRead);
			mEmulator.append(mReceiveBuffer, 0, bytesRead);
		} catch (InterruptedException e) {
		}

		if (mNotify != null) {
			mNotify.onUpdate();
		}
	}

	public void updatePrefs(TermSettings settings) {
		mSettings = settings;
		if (mEmulator == null) {
			// Not initialized yet, we'll pick up the settings then
			return;
		}

		mEmulator.updatePrefs(settings);

		int[] colorScheme = settings.getColorScheme();
		mTranscriptScreen.setDefaultColors(colorScheme[0], colorScheme[2]);
	}

	public void reset() {
		mEmulator.reset();
		if (mNotify != null) {
			mNotify.onUpdate();
		}
	}


	public void finish() {
		mIsRunning = false;
		mTranscriptScreen.finish();
		cleanup();
	}

	protected void cleanup() 
	{
		if (mPollingThread != null) {
			try {
				mPollingThread.interrupt();
				mPollingThread = null;
			} catch (Exception e) {
				Log.w(TermSession.class.getName(),
						"polling thread interrupt error", e);
			}
		}

		if (mShell != null) {
			try {
				mShell.close();
				mShell = null;
				mTermIn = null;
				mTermOut = null;
			} catch (Exception e) {
				Log.w(TermSession.class.getName(), "shell.close error", e);
			}
		}

		if (mSession != null) {
			try {
				mSession.close();
				mSession = null;
			} catch (Exception e) {
				Log.w(TermSession.class.getName(), "session.close error", e);
			}
		}

		if (mSsh != null) {
			try {
				mSsh.disconnect();
				mSsh = null;
			} catch (Exception e) {
				Log.w(TermSession.class.getName(), "ssh.disconnect error", e);
			}
		}
	}
}
