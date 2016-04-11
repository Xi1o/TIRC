package fr.upem.net.tcp.client;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.Objects;

import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

@SuppressWarnings("serial")
public class ClientGUI extends JFrame {
	private static final int WIDTH = 640;
	private static final int HEIGHT = 480;
	private static final String TITLE = "TIRC Client";
	private final Client client;
	private final JTextArea chatArea;

	public ClientGUI(Client client) {
		super(); // construct a new frame
		this.client = client;
		chatArea = buildChatArea();
		setFrameSettings();
		setBehaviorOnClose();
		buildComponents();
	}

	private void buildComponents() {
		JTextField inputArea = buildInputArea();
		buildScrollbar(chatArea);
		buildMenu();

		inputArea.addKeyListener(new KeyAdapter() {
			public void keyPressed(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_ENTER) {
					String text = inputArea.getText();
					if (text.isEmpty()) {
						return; // ignore event if no input text
					}
					try {
						client.processInput(Objects.requireNonNull(text));
					} catch (IOException ioe) {
					}
					// auto-scroll to bottom
					chatArea.setCaretPosition(chatArea.getDocument().getLength());
					inputArea.setText(""); // clear text in input area
				}
			}
		});
		
		pack(); // must be after building all components
	}

	private void setFrameSettings() {
		setTitle(TITLE);
		setResizable(true);
		setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		setVisible(true);
	}

	private void setBehaviorOnClose() {
		// behavior when manually closing the window
		addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                try {
                	client.processInput("/quit");
					exit();
				} catch (IOException ioe) {
				}
            }
        });
	}
	
	private JTextArea buildChatArea() {
		JTextArea chatArea = new JTextArea();
		chatArea.setLineWrap(true); // auto new-lines
		chatArea.setWrapStyleWord(true); // don't cut words on new-lines
		chatArea.setEditable(false); // can't edit inside text
		return chatArea;
	}

	private void buildScrollbar(JTextArea ta) {
		JScrollPane scroller = new JScrollPane(ta);
		scroller.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroller.setPreferredSize(new Dimension(WIDTH, HEIGHT));
		add(scroller, BorderLayout.CENTER); // add it to ClientGUI
	}

	private JTextField buildInputArea() {
		JTextField inputArea = new JTextField();
		add(inputArea, BorderLayout.SOUTH);
		return inputArea;
	}

	// TODO : provide behavior on click
	private void buildMenu() {
		JMenuBar menu = new JMenuBar();
		setJMenuBar(menu);
		JMenu menuFile = new JMenu("File");
		JMenu menuHelp = new JMenu("Help");
		menu.add(menuFile);
		menu.add(menuHelp);
		JMenuItem menuFile_Disconnect = new JMenuItem("Disconnect");
		JMenuItem menuHelp_Commands = new JMenuItem("Commands");
		menuFile.add(menuFile_Disconnect);
		menuHelp.add(menuHelp_Commands);
	}

	/**
	 * Prints a String in the client GUI, and then terminate the line.
	 * 
	 * @param string
	 *            The string to be printed.
	 */
	public void println(String string) {
		chatArea.append(string + "\n");
	}

	/**
	 * Closes the graphical user interface and terminates the whole client.
	 * @throws IOException 
	 */
	public void exit() throws IOException {
		client.close();
		dispose();
	}
}