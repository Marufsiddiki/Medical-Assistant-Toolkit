// Added for SQLite
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

// PDFBox imports
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import javafx.stage.Modality;
import javafx.stage.Stage;

public class MedAssistProApp extends Application {
    private Stage stage;
    private Scene loginScene, signupScene, patientScene, mainScene;

    // Patient and user data
    private String loggedUser = null;
    private final Map<String, String> users = new HashMap<>();
    private Patient patient = new Patient();

    // Main UI components
    private ListView<String> diagnosisList;
    private TextArea detailsArea;
    private TextArea prescriptionArea;

    // Disease Database
    private final Map<String, DiseaseInfo> diseaseDB = new HashMap<>();
    private final Map<String, List<String>> symptomToDisease = new HashMap<>();

    // Manual input fields
    private TextField manualMedicineField;
    private TextField manualDosageField;
    private TextField manualTestField;
    private TextArea manualRemedyArea;
    private ListView<String> manualMedicinesList;
    private ListView<String> manualTestsList;

    // SQLite DB connection
    private Connection conn;

    // UI images for styling
    private Image logoImage;
    private Image bgImage;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        setupDatabase();

        // load images used for styling (place assets in project root "assets" folder)
        try {
            logoImage = new Image("file:assets/logo.png", true);
            bgImage = new Image("file:assets/bg.jpg", true);
        } catch (Exception ex) {
            // ignore if images not present
            logoImage = null;
            bgImage = null;
        }

        // initialize SQLite DB
        initDatabase();
        // load users from DB into memory
        loadUsersFromDB();

        loginScene = createLoginScene();
        signupScene = createSignupScene();
        patientScene = createPatientScene();
        mainScene = createMainScene();

        // Load the custom theme stylesheet for all scenes (file-based resource)
        String css = "file:resources/medassist.css";
        try {
            loginScene.getStylesheets().add(css);
            signupScene.getStylesheets().add(css);
            patientScene.getStylesheets().add(css);
            mainScene.getStylesheets().add(css);
        } catch (Exception ex) {
            // ignore if stylesheet not found
        }

        stage.setScene(loginScene);
        stage.setTitle("MedAssist Pro - Login");
        stage.show();
    }

    // Simple text wrapping for PDF body using PDType1Font metrics
    private java.util.List<String> wrapText(String text, PDType1Font font, int fontSize, float maxWidth) throws IOException {
        java.util.List<String> out = new java.util.ArrayList<>();
        String[] paragraphs = text.split("\\r?\\n");
        for (String para : paragraphs) {
            String[] words = para.split(" ");
            StringBuilder line = new StringBuilder();
            for (String w : words) {
                String trial = line.length() == 0 ? w : line + " " + w;
                float textWidth = font.getStringWidth(trial) / 1000 * fontSize;
                if (textWidth > maxWidth) {
                    if (line.length() > 0) {
                        out.add(line.toString());
                        line = new StringBuilder(w);
                    } else {
                        // single long word: hard break
                        out.add(trial);
                        line = new StringBuilder();
                    }
                } else {
                    line = new StringBuilder(trial);
                }
            }
            if (line.length() > 0) out.add(line.toString());
            // blank line between paragraphs
            out.add("");
        }
        return out;
    }

    // Helpers to parse generated prescription plain text into sections
    private static String extractLineValue(java.util.List<String> lines, String prefix) {
        for (String l : lines) {
            if (l == null) continue;
            String t = l.trim();
            if (t.startsWith(prefix)) {
                return t.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static int indexOfPrefix(java.util.List<String> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l == null) continue;
            if (l.trim().startsWith(prefix)) return i;
        }
        return -1;
    }

    private static java.util.List<String> extractSection(java.util.List<String> lines, String startPrefix, String stopPrefix) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int si = -1;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l == null) continue;
            if (l.trim().startsWith(startPrefix)) { si = i + 1; break; }
        }
        if (si < 0) return out;
        for (int i = si; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l == null) continue;
            String t = l.trim();
            if (t.isEmpty()) continue;
            if (stopPrefix != null && !stopPrefix.isEmpty() && t.startsWith(stopPrefix)) break;
            // stop when encountering another common header
            if (t.matches("^(Recommended Tests:|Advice/Remedy:|Case Study).*$")) break;
            out.add(t);
        }
        return out;
    }

    // ---------------- LOGIN SCENE 
    private Scene createLoginScene() {
        VBox root = new VBox(10);
        root.getStyleClass().add("root-container");
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(20));

        // background: gradient + optional background image
        // background handled by CSS
        root.getStyleClass().add("login-bg");

        // logo
        if (logoImage != null) {
            ImageView logoView = new ImageView(logoImage);
            logoView.setFitWidth(140);
            logoView.setPreserveRatio(true);
            logoView.setSmooth(true);
            root.getChildren().add(0, logoView);
        }

        Label title = new Label("Login to MedAssist Pro");
        title.getStyleClass().add("header-label");

        TextField usernameField = new TextField();
        usernameField.setPromptText("Username");
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");

        Label msg = new Label();

        Button loginBtn = new Button("Login");
        // professional button style + shadow
        loginBtn.getStyleClass().add("action-button");
        loginBtn.setEffect(new DropShadow(6, Color.rgb(0,0,0,0.2)));

        loginBtn.setOnAction(e -> {
            String u = usernameField.getText().trim();
            String p = passwordField.getText().trim();
            if (u.isEmpty() || p.isEmpty()) {
                msg.setText("Enter username and password");
                return;
            }
            String storedHash = users.get(u);
            if (storedHash != null && storedHash.equals(hashPassword(p))) {
                loggedUser = u;
                stage.setScene(patientScene);
            } else {
                msg.setText("Invalid username or password!");
            }
        });

        Button signupBtn = new Button("Sign Up");
        signupBtn.getStyleClass().add("print-button");
        signupBtn.setOnAction(e -> stage.setScene(signupScene));

        root.getChildren().addAll(title, usernameField, passwordField, loginBtn, signupBtn, msg);
        return new Scene(root, 420, 380);
    }

    // ---------------- SIGNUP SCENE ----------------
    private Scene createSignupScene() {
        VBox root = new VBox(10);
        root.getStyleClass().add("root-container");
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(20));

        // background handled by CSS
        root.getStyleClass().add("signup-bg");

        if (logoImage != null) {
            ImageView logoView = new ImageView(logoImage);
            logoView.setFitWidth(120);
            logoView.setPreserveRatio(true);
            root.getChildren().add(0, logoView);
        }

        Label title = new Label("Create New Account");
        title.getStyleClass().add("header-label");
        TextField usernameField = new TextField();
        usernameField.setPromptText("New Username");
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("New Password");

        Label msg = new Label();

        Button createBtn = new Button("Sign Up");
        createBtn.getStyleClass().add("action-button");
        createBtn.setEffect(new DropShadow(5, Color.rgb(0,0,0,0.15)));
        createBtn.setOnAction(e -> {
            String u = usernameField.getText().trim();
            String p = passwordField.getText().trim();
            if (u.isEmpty() || p.isEmpty()) {
                msg.setText("Fill all fields!");
                return;
            }
            if (users.containsKey(u)) {
                msg.setText("User already exists!");
                return;
            }
            String hash = hashPassword(p);
            boolean saved = saveUserToDB(u, hash);
            if (saved) {
                users.put(u, hash);
                msg.setText("Account created! Please login.");
                stage.setScene(loginScene);
            } else {
                msg.setText("Failed to create account (DB error).");
            }
        });

        Button backBtn = new Button("Back to Login");
        backBtn.getStyleClass().add("print-button");
        backBtn.setOnAction(e -> stage.setScene(loginScene));

        root.getChildren().addAll(title, usernameField, passwordField, createBtn, backBtn, msg);
        return new Scene(root, 420, 380);
    }

    // ---------------- PATIENT INFO SCENE ----------------
    private Scene createPatientScene() {
        VBox root = new VBox(10);
        root.getStyleClass().add("root-container");
        root.setAlignment(Pos.CENTER_LEFT);
        root.setPadding(new Insets(20));

        // background handled by CSS
        root.getStyleClass().add("patient-bg");

        if (logoImage != null) {
            ImageView logoView = new ImageView(logoImage);
            logoView.setFitWidth(110);
            logoView.setPreserveRatio(true);
            root.getChildren().add(0, logoView);
        }

        Label title = new Label("Enter Patient Information");
        title.getStyleClass().add("header-label");

        TextField nameField = new TextField();
        nameField.setPromptText("Patient Name");
        TextField ageField = new TextField();
        ageField.setPromptText("Age");
        ComboBox<String> genderBox = new ComboBox<>(FXCollections.observableArrayList("Male", "Female", "Other"));
        genderBox.setPromptText("Gender");

        TextField tempField = new TextField();
        tempField.setPromptText("Temperature (°F)");
        TextField bpField = new TextField();
        bpField.setPromptText("Blood Pressure (e.g., 120/80)");
        TextField weightField = new TextField();
        weightField.setPromptText("Weight (kg)");

        Label msg = new Label();

        Button proceedBtn = new Button("Proceed");
        proceedBtn.getStyleClass().add("action-button");
        proceedBtn.setEffect(new DropShadow(5, Color.rgb(0,0,0,0.18)));
        proceedBtn.setOnAction(e -> {
            if (nameField.getText().isEmpty() || ageField.getText().isEmpty() || genderBox.getValue() == null ||
                    tempField.getText().isEmpty() || bpField.getText().isEmpty()) {
                msg.setText("Please fill all fields!");
                return;
            }
                patient = new Patient(
                    nameField.getText(),
                    ageField.getText(),
                    genderBox.getValue(),
                    tempField.getText(),
                    bpField.getText(),
                    weightField.getText()
                );

            // Save basic patient info to DB (insert)
            saveOrUpdatePatientInDB(patient);

            stage.setScene(mainScene);
        });

        root.getChildren().addAll(title, nameField, ageField, genderBox, tempField, bpField, weightField, proceedBtn, msg);
        return new Scene(root, 420, 460);
    }

    // ---------------- MAIN APP SCENE ----------------
    private Scene createMainScene() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-container");
        // background handled by CSS
        root.getStyleClass().add("main-bg");

        // Symptoms & Diagnosis
        VBox leftPanel = new VBox(15);
        leftPanel.getStyleClass().add("section-panel");
        leftPanel.setPadding(new Insets(15));

        Label symptomLabel = new Label("Select Symptoms:");
        symptomLabel.getStyleClass().add("panel-title");
        VBox symptomBox = new VBox(5);
        for (String symptom : symptomToDisease.keySet()) {
            CheckBox cb = new CheckBox(symptom);
            cb.getStyleClass().add("symptom-checkbox");
            cb.setOnAction(e -> updateDiagnosis(symptomBox));
            symptomBox.getChildren().add(cb);
        }

        // allow symptom box and diagnosis list to share available vertical space
        symptomBox.setPrefHeight(320);
        symptomBox.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(symptomBox, Priority.ALWAYS);

        diagnosisList = new ListView<>();
        diagnosisList.getStyleClass().add("diagnosis-list");
        diagnosisList.setPlaceholder(new Label("Select symptoms first"));
        diagnosisList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) showDetails(newVal);
        });
        diagnosisList.setPrefHeight(320);
        diagnosisList.setMinHeight(180);
        diagnosisList.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(diagnosisList, Priority.ALWAYS);

        Label possibleLbl = new Label("Possible Diseases:");
        possibleLbl.getStyleClass().add("panel-title");
        leftPanel.getChildren().addAll(symptomLabel, symptomBox, possibleLbl, diagnosisList);

        // Details Panel
        VBox centerPanel = new VBox(10);
        centerPanel.getStyleClass().add("section-panel");
        centerPanel.setPadding(new Insets(15));
        detailsArea = new TextArea();
        detailsArea.setWrapText(true);
        detailsArea.setEditable(false);
        detailsArea.setPrefHeight(260);
        detailsArea.getStyleClass().add("detail-area");

        // ---------- Referral Note ----------
        Label caseLabel = new Label("Referral Note :");
        caseLabel.getStyleClass().add("panel-title");

        GridPane caseGrid = new GridPane();
        caseGrid.setHgap(10);
        caseGrid.setVgap(8);
        caseGrid.setPadding(new Insets(8));

        Label doctorLbl = new Label("Doctor Name:");
        doctorLbl.getStyleClass().add("panel-title");
        TextField doctorField = new TextField();
        doctorField.setPromptText("Dr. John Doe");

        Label hospitalLbl = new Label("Hospital / Clinic:");
        hospitalLbl.getStyleClass().add("panel-title");
        TextField hospitalField = new TextField();
        hospitalField.setPromptText("Hospital Name");

        Label contactLbl = new Label("Contact:");
        contactLbl.getStyleClass().add("panel-title");
        TextField contactField = new TextField();
        contactField.setPromptText("Phone / Email");

        Label dateLbl = new Label("Report Date:");
        dateLbl.getStyleClass().add("panel-title");
        DatePicker reportDatePicker = new DatePicker(LocalDate.now());

        Label summaryLbl = new Label("Short Report Summary:");
        summaryLbl.getStyleClass().add("panel-title");
        TextArea summaryArea = new TextArea();
        summaryArea.setPromptText("Short case summary for authority (max 1000 chars)");
        summaryArea.setPrefRowCount(4);
        summaryArea.setWrapText(true);

        Button saveCaseBtn = new Button("Save Case Study");
        Label caseMsg = new Label();

        // Layout positions
        caseGrid.add(doctorLbl, 0, 0);
        caseGrid.add(doctorField, 1, 0);
        caseGrid.add(hospitalLbl, 0, 1);
        caseGrid.add(hospitalField, 1, 1);
        caseGrid.add(contactLbl, 0, 2);
        caseGrid.add(contactField, 1, 2);
        caseGrid.add(dateLbl, 0, 3);
        caseGrid.add(reportDatePicker, 1, 3);
        caseGrid.add(summaryLbl, 0, 4);
        caseGrid.add(summaryArea, 1, 4);
        caseGrid.add(saveCaseBtn, 1, 5);
        caseGrid.add(caseMsg, 1, 6);

        saveCaseBtn.setOnAction(e -> {
            String dName = doctorField.getText().trim();
            String hosp = hospitalField.getText().trim();
            String contact = contactField.getText().trim();
            LocalDate dt = reportDatePicker.getValue();
            String summ = summaryArea.getText().trim();

            if (dName.isEmpty() || hosp.isEmpty() || dt == null || summ.isEmpty()) {
                caseMsg.setText("Please fill Doctor, Hospital, Date and Summary.");
                return;
            }
            // Save into patient caseStudy
            patient.caseStudy = new CaseStudy(dName, hosp, contact, dt, summ);
            // update DB record
            saveOrUpdatePatientInDB(patient);
            caseMsg.setText("Referral Note saved.");
        });

         Label diseaseDetailsLbl = new Label("Disease Details:");
        diseaseDetailsLbl.getStyleClass().add("panel-title");
        centerPanel.getChildren().addAll(diseaseDetailsLbl, detailsArea, new Separator(), caseLabel, caseGrid);

        // Prescription Panel with Manual Input Section
        VBox rightPanel = new VBox(10);
        rightPanel.getStyleClass().add("section-panel");
        rightPanel.setPadding(new Insets(15));

        // New button to view stored patients
        HBox topButtons = new HBox(8);
        Button viewPatientsBtn = new Button("View Patients");
        // icon + style
        if (logoImage != null) {
            ImageView ico = new ImageView(logoImage);
            ico.setFitWidth(18);
            ico.setFitHeight(18);
            viewPatientsBtn.setGraphic(ico);
        }
        viewPatientsBtn.getStyleClass().add("accent-button");
        viewPatientsBtn.setEffect(new DropShadow(5, Color.rgb(0,0,0,0.18)));
        viewPatientsBtn.setOnAction(e -> openPatientDetailsStage());
        Button savePatientBtn = new Button("Save Patient Record");
        savePatientBtn.getStyleClass().add("success-button");
        savePatientBtn.setEffect(new DropShadow(5, Color.rgb(0,0,0,0.18)));
        savePatientBtn.setOnAction(e -> {
            saveOrUpdatePatientInDB(patient);
        });
        topButtons.getChildren().addAll(viewPatientsBtn, savePatientBtn);

        prescriptionArea = new TextArea();
        prescriptionArea.setWrapText(true);
        prescriptionArea.setEditable(false);
        prescriptionArea.setPrefHeight(350);
        prescriptionArea.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(prescriptionArea, Priority.ALWAYS);
        prescriptionArea.getStyleClass().add("prescription-box");

        // Manual Input Section
        VBox manualInputSection = createManualInputSection();

        Button generateBtn = new Button("Generate Prescription");
        generateBtn.getStyleClass().addAll("purple-button", "generate-button");
        generateBtn.setEffect(new DropShadow(6, Color.rgb(0,0,0,0.18)));
        generateBtn.setOnAction(e -> {
            generatePrescription();
            // save latest manual entries to DB as well
            saveOrUpdatePatientInDB(patient);
        });

        Button exportPdfBtn = new Button("Export PDF");
        exportPdfBtn.getStyleClass().add("action-button");
        exportPdfBtn.setOnAction(e -> exportPrescriptionToPDF());

        HBox actionBtnBox = new HBox(8);
        actionBtnBox.getChildren().addAll(generateBtn, exportPdfBtn);

        Label prescriptionLbl = new Label("Prescription:");
        prescriptionLbl.getStyleClass().add("panel-title");
        rightPanel.getChildren().addAll(topButtons, prescriptionLbl, prescriptionArea, manualInputSection, actionBtnBox);

        root.setLeft(leftPanel);
        root.setCenter(centerPanel);
        root.setRight(rightPanel);

        return new Scene(root, 1200, 700);
    }

    // ---------------- MANUAL INPUT SECTION ----------------
    private VBox createManualInputSection() {
        VBox manualSection = new VBox(8);
        manualSection.getStyleClass().add("section-panel");
        manualSection.setPadding(new Insets(10, 0, 0, 0));
        // styling from CSS via .section-panel

        Label manualLabel = new Label("Add Manual Items to Prescription:");
        manualLabel.getStyleClass().add("header-label");

        // Medicine Input
        HBox medicineBox = new HBox(5);
        medicineBox.setAlignment(Pos.CENTER_LEFT);
        manualMedicineField = new TextField();
        manualMedicineField.setPromptText("Medicine Name");
        manualMedicineField.setPrefWidth(180);

        manualDosageField = new TextField();
        manualDosageField.setPromptText("Dosage");
        manualDosageField.setPrefWidth(120);

        Button addMedicineBtn = new Button("Add Medicine");
        addMedicineBtn.setOnAction(e -> addManualMedicine());

        Label medLbl = new Label("Med:");
        medLbl.getStyleClass().add("panel-title");
        Label doseLbl = new Label("Dose:");
        doseLbl.getStyleClass().add("panel-title");
        medicineBox.getChildren().addAll(medLbl, manualMedicineField, doseLbl, manualDosageField, addMedicineBtn);

        // Test Input
        HBox testBox = new HBox(5);
        testBox.setAlignment(Pos.CENTER_LEFT);
        manualTestField = new TextField();
        manualTestField.setPromptText("Test Name");
        manualTestField.setPrefWidth(200);

        Button addTestBtn = new Button("Add Test");
        addTestBtn.setOnAction(e -> addManualTest());

        Label testLbl = new Label("Test:");
        testLbl.getStyleClass().add("panel-title");
        testBox.getChildren().addAll(testLbl, manualTestField, addTestBtn);

        // Remedy Input
        VBox remedyBox = new VBox(5);
        manualRemedyArea = new TextArea();
        manualRemedyArea.setPromptText("Additional advice/remedy");
        manualRemedyArea.setPrefRowCount(2);
        manualRemedyArea.setWrapText(true);

        Button addRemedyBtn = new Button("Add Remedy");
        addRemedyBtn.setOnAction(e -> addManualRemedy());

        Label adviceLbl = new Label("Additional Advice:");
        adviceLbl.getStyleClass().add("panel-title");
        remedyBox.getChildren().addAll(adviceLbl, manualRemedyArea, addRemedyBtn);

        // Lists to show added items
        HBox listsBox = new HBox(12);
        listsBox.setPrefHeight(220);

        VBox medicineListBox = new VBox(6);
        medicineListBox.setPrefWidth(320);
        medicineListBox.setMaxWidth(Double.MAX_VALUE);
        Label addedMedsLbl = new Label("Added Medicines:");
        addedMedsLbl.getStyleClass().add("panel-title");
        medicineListBox.getChildren().add(addedMedsLbl);
        manualMedicinesList = new ListView<>();
        manualMedicinesList.setPrefHeight(180);
        manualMedicinesList.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(manualMedicinesList, Priority.ALWAYS);
        medicineListBox.getChildren().add(manualMedicinesList);

        VBox testListBox = new VBox(6);
        testListBox.setPrefWidth(320);
        testListBox.setMaxWidth(Double.MAX_VALUE);
        Label addedTestsLbl = new Label("Added Tests:");
        addedTestsLbl.getStyleClass().add("panel-title");
        testListBox.getChildren().add(addedTestsLbl);
        manualTestsList = new ListView<>();
        manualTestsList.setPrefHeight(180);
        manualTestsList.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(manualTestsList, Priority.ALWAYS);
        testListBox.getChildren().add(manualTestsList);

        // allow the two list boxes to grow and fill available space
        HBox.setHgrow(medicineListBox, Priority.ALWAYS);
        HBox.setHgrow(testListBox, Priority.ALWAYS);
        VBox.setVgrow(medicineListBox, Priority.ALWAYS);
        VBox.setVgrow(testListBox, Priority.ALWAYS);
        listsBox.getChildren().addAll(medicineListBox, testListBox);

        // Clear buttons
        HBox clearBox = new HBox(5);
        Button clearMedsBtn = new Button("Clear Medicines");
        clearMedsBtn.setOnAction(e -> clearManualMedicines());

        Button clearTestsBtn = new Button("Clear Tests");
        clearTestsBtn.setOnAction(e -> clearManualTests());

        Button clearRemedyBtn = new Button("Clear Remedy");
        clearRemedyBtn.setOnAction(e -> clearManualRemedy());

        clearBox.getChildren().addAll(clearMedsBtn, clearTestsBtn, clearRemedyBtn);

        manualSection.getChildren().addAll(
            manualLabel, medicineBox, testBox, remedyBox,
            listsBox, clearBox
        );

        return manualSection;
    }

    private void addManualMedicine() {
        String medicine = manualMedicineField.getText().trim();
        String dosage = manualDosageField.getText().trim();

        if (!medicine.isEmpty()) {
            String medicineEntry = medicine + (dosage.isEmpty() ? "" : " - " + dosage);
            if (patient.manualMedicines == null) {
                patient.manualMedicines = FXCollections.observableArrayList();
            }
            patient.manualMedicines.add(medicineEntry);
            manualMedicinesList.setItems(patient.manualMedicines);
            manualMedicineField.clear();
            manualDosageField.clear();
        }
    }

    private void addManualTest() {
        String test = manualTestField.getText().trim();

        if (!test.isEmpty()) {
            if (patient.manualTests == null) {
                patient.manualTests = FXCollections.observableArrayList();
            }
            patient.manualTests.add(test);
            manualTestsList.setItems(patient.manualTests);
            manualTestField.clear();
        }
    }

    private void addManualRemedy() {
        String remedy = manualRemedyArea.getText().trim();

        if (!remedy.isEmpty()) {
            patient.manualRemedy = remedy;
            manualRemedyArea.clear();
        }
    }

    private void clearManualMedicines() {
        if (patient.manualMedicines != null) {
            patient.manualMedicines.clear();
        }
        manualMedicinesList.getItems().clear();
    }

    private void clearManualTests() {
        if (patient.manualTests != null) {
            patient.manualTests.clear();
        }
        manualTestsList.getItems().clear();
    }

    private void clearManualRemedy() {
        patient.manualRemedy = null;
        manualRemedyArea.clear();
    }

    // ---------------- DATABASE ----------------
    private void setupDatabase() {
        // Symptom to Disease mapping
        symptomToDisease.put("Headache", List.of("Migraine", "Tension Headache"));
        symptomToDisease.put("Fever", List.of("Flu", "Dengue", "COVID-19"));
        symptomToDisease.put("Cough", List.of("Flu", "Bronchitis", "COVID-19"));
        symptomToDisease.put("Sore Throat", List.of("Flu", "Strep Throat", "COVID-19"));
        symptomToDisease.put("Runny Nose", List.of("Allergic Rhinitis", "Common Cold", "Flu"));
        symptomToDisease.put("Loss of Smell", List.of("COVID-19", "Sinusitis"));
        symptomToDisease.put("Fatigue", List.of("Anemia", "Diabetes", "Flu"));
        symptomToDisease.put("Body Pain", List.of("Flu", "Dengue", "Typhoid"));
        symptomToDisease.put("Nausea", List.of("Food Poisoning", "Migraine"));
        symptomToDisease.put("Diarrhea", List.of("Food Poisoning", "Gastroenteritis", "Typhoid"));
        symptomToDisease.put("Vomiting", List.of("Food Poisoning", "Gastroenteritis"));
        symptomToDisease.put("Abdominal Pain", List.of("Gastroenteritis", "Typhoid"));
        symptomToDisease.put("Shortness of Breath", List.of("Asthma", "Pneumonia", "COVID-19"));
        symptomToDisease.put("Chest Pain", List.of("Heart Disease", "Angina"));
        symptomToDisease.put("Chest Tightness", List.of("Asthma", "Angina"));
        symptomToDisease.put("Rash", List.of("Dengue", "Allergic Reaction"));
        symptomToDisease.put("Frequent Urination", List.of("UTI", "Diabetes"));

        // Disease database with Virus/Bacteria & Variant names
        // Migraine: include triggers, aura, associated symptoms and common acute/ preventive meds
        diseaseDB.put("Migraine", new DiseaseInfo(
            "Neurological", "Migraine",
            "Hydration, rest in a dark quiet room; avoid triggers; consider early acute therapy",
            List.of(
                "When did the current headache start?",
                "Is there a visual aura or flashing lights before pain?",
                "Any nausea or vomiting?",
                "Is pain unilateral and pulsating?",
                "How many days per month do you have severe headaches?"
            ),
            Map.ofEntries(
                Map.entry("Sumatriptan", "50-100mg at onset (per guidelines)"),
                Map.entry("Zolmitriptan", "2.5mg at onset"),
                Map.entry("NSAID (Ibuprofen)", "200-400mg PRN"),
                Map.entry("Antiemetic (Metoclopramide)", "10mg PRN")
            ),
            List.of("Neurological exam if atypical; MRI if red flags")
        ));

        diseaseDB.put("Tension Headache", new DiseaseInfo(
            "Neurological", "Tension-type Headache",
            "Analgesia, relaxation techniques, posture correction",
            List.of(
                "Is the pain band-like or pressure-like across the head?",
                "Any neck muscle tightness or recent stressors?",
                "Frequency and duration of headaches?"
            ),
            Map.of("Ibuprofen", "200-400mg PRN", "Naproxen", "220mg PRN"),
            List.of("Usually clinical; physiotherapy for chronic cases")
        ));

        diseaseDB.put("Flu", new DiseaseInfo(
            "Influenza Virus", "Seasonal Influenza",
            "Rest, hydration, consider antivirals if within 48 hours and high risk",
            List.of(
                "Onset: sudden or gradual?",
                "Any high fever or chills?",
                "Sore throat, cough, body aches?",
                "Are you in a high-risk group (older age, chronic disease)?"
            ),
            Map.of("Oseltamivir", "75mg twice daily for 5 days (if indicated)", "Paracetamol", "500-1000mg PRN for fever"),
            List.of("Viral swab (if required), symptomatic monitoring")
        ));

        diseaseDB.put("Dengue", new DiseaseInfo(
            "Dengue Virus", "Dengue",
            "Aggressive oral hydration, avoid NSAIDs/aspirin; monitor platelets and warning signs",
            List.of(
                "High fever with severe myalgia/arthralgia?",
                "Any bleeding (gums, nose, petechiae)?",
                "Rash or abdominal pain?",
                "Any persistent vomiting or lethargy?"
            ),
            Map.of("Paracetamol", "500-1000mg PRN (avoid NSAIDs)"),
            List.of("CBC, Dengue NS1/IgM; monitor platelets and hematocrit")
        ));

        diseaseDB.put("Strep Throat", new DiseaseInfo(
            "Bacterial (Streptococcus)", "Streptococcal Pharyngitis",
            "Oral antibiotics (penicillin/ amoxicillin) when confirmed or high clinical suspicion; analgesics for pain",
            List.of(
                "Sore throat onset and severity?",
                "Fever present?",
                "Any cough (usually absent in strep)?",
                "Tender anterior cervical lymph nodes?"
            ),
            Map.of("Amoxicillin", "500mg TID for 7-10 days", "Phenoxymethylpenicillin", "500mg TID"),
            List.of("Throat swab / rapid antigen test")
        ));

        diseaseDB.put("Allergic Rhinitis", new DiseaseInfo(
            "Allergic", "Allergic Rhinitis",
            "Avoid triggers, use oral antihistamines and intranasal steroids for persistent symptoms",
            List.of(
                "Is rhinitis seasonal or perennial?",
                "Itchy/watery eyes?",
                "Response to antihistamines?"
            ),
            Map.of("Cetirizine", "10mg once daily", "Fluticasone nasal spray", "2 sprays each nostril daily"),
            List.of("Allergy testing if unclear")
        ));

        diseaseDB.put("Pneumonia", new DiseaseInfo(
            "Bacterial/Viral", "Pneumonia",
            "Antibiotics when bacterial; oxygen and supportive care if hypoxic",
            List.of(
                "Fever and productive cough?",
                "Shortness of breath or tachypnea?",
                "Chest pain on breathing?"
            ),
            Map.of("Amoxicillin", "500mg TID", "Azithromycin", "500mg once daily (if atypical suspected)"),
            List.of("Chest X-ray, CBC, blood cultures if severe")
        ));

        diseaseDB.put("Gastroenteritis", new DiseaseInfo(
            "Infectious", "Acute Gastroenteritis",
            "Oral rehydration, dietary measures; antiemetics/antidiarrheals as appropriate",
            List.of(
                "Onset and frequency of diarrhea/vomiting?",
                "Any blood in stools?",
                "Recent travel or suspect food source?"
            ),
            Map.of("ORS", "As needed", "Ondansetron", "4mg PRN for vomiting"),
            List.of("Stool culture if bloody or prolonged; consider C. difficile testing as indicated")
        ));

        diseaseDB.put("Asthma", new DiseaseInfo(
            "Respiratory", "Asthma",
            "Short-acting bronchodilator for relief; inhaled corticosteroids for control",
            List.of(
                "Wheezing or chest tightness?",
                "Night-time symptoms?",
                "Use of rescue inhaler frequency?"
            ),
            Map.of("Salbutamol inhaler", "2 puffs PRN", "Budesonide inhaler", "200-400mcg BD"),
            List.of("Peak flow/ spirometry when stable")
        ));

        diseaseDB.put("UTI", new DiseaseInfo(
            "Bacterial", "Urinary Tract Infection",
            "Antibiotics guided by local resistance; hydration and urinary hygiene",
            List.of(
                "Dysuria (pain on passing urine)?",
                "Frequency or urgency?",
                "Fever or flank pain?"
            ),
            Map.of("Nitrofurantoin", "100mg BD for 5 days", "Trimethoprim", "200mg BD for 3 days"),
            List.of("Urine dipstick and culture")
        ));

        diseaseDB.put("Typhoid", new DiseaseInfo(
            "Bacterial", "Salmonella Typhi",
            "Appropriate antibiotics based on sensitivity, hydration",
            List.of(
                "Prolonged fever?",
                "Abdominal pain or constipation/diarrhea?",
                "Recent travel to endemic area?"
            ),
            Map.of("Cefixime", "200mg BD", "Azithromycin", "500mg once daily"),
            List.of("Blood culture (gold standard); Widal is less specific")
        ));

        // keep Dengue existing advice entry
        diseaseDB.put("Dengue", diseaseDB.get("Dengue"));

        diseaseDB.put("Anemia", new DiseaseInfo("Nutritional/haematologic", "Iron-deficiency Anemia",
            "Iron supplementation, dietary correction and investigate causes",
            List.of("Degree of fatigue and exertional shortness of breath?", "Pale conjunctiva or koilonychia?", "Any chronic blood loss?"),
            Map.of("Ferrous sulfate", "325mg once daily", "Folic acid", "1mg once daily"),
            List.of("CBC, peripheral smear, iron studies")));
    }

    private void updateDiagnosis(VBox symptomBox) {
        List<String> selected = new ArrayList<>();
        for (var node : symptomBox.getChildren()) {
            if (node instanceof CheckBox cb && cb.isSelected()) {
                selected.add(cb.getText());
            }
        }
        // Simple matching: count diseases by number of matching symptoms
        Map<String, Integer> diseaseCount = new HashMap<>();
        for (String s : selected) {
            for (String d : symptomToDisease.getOrDefault(s, List.of())) {
                diseaseCount.put(d, diseaseCount.getOrDefault(d, 0) + 1);
            }
        }
        int total = selected.size();
        ObservableList<String> possible = diseaseCount.entrySet().stream()
                .map(e -> e.getKey() + " (" + (int)((e.getValue()*100.0)/total) + "%)")
                .collect(FXCollections::observableArrayList, ObservableList::add, ObservableList::addAll);
        diagnosisList.setItems(possible);
    }

    private void showDetails(String selectedDisease) {
        String diseaseName = selectedDisease.split(" \\(")[0];
        DiseaseInfo info = diseaseDB.get(diseaseName);
        if (info == null) {
            detailsArea.setText("No details available.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Virus/Bacteria: ").append(info.icd).append("\n");
        sb.append("Variant: ").append(info.cpt).append("\n\n");
        sb.append("Questions to ask:\n");
        info.questions.forEach(q -> sb.append(" - ").append(q).append("\n"));
        sb.append("\nRemedy/Advice: ").append(info.remedy).append("\n");
        sb.append("\nRecommended Tests:\n");
        info.tests.forEach(t -> sb.append(" - ").append(t).append("\n"));
        detailsArea.setText(sb.toString());
    }

    private void generatePrescription() {
        String selected = diagnosisList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            prescriptionArea.setText("Please select a disease.");
            return;
        }
        String diseaseName = selected.split(" \\(")[0];
        DiseaseInfo info = diseaseDB.get(diseaseName);
        if (info == null) {
            prescriptionArea.setText("No prescription available.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Patient Name: ").append(patient.name).append("\n");
        sb.append("Age: ").append(patient.age).append(", Gender: ").append(patient.gender).append("\n");
        sb.append("Temperature: ").append(patient.temp).append("°F, BP: ").append(patient.bp);
        if (patient.weight != null && !patient.weight.isEmpty()) {
            sb.append(", Weight: ").append(patient.weight).append(" kg");
        }
        sb.append("\n\n");
        sb.append("Diagnosis: ").append(diseaseName).append("\n");
        sb.append("Virus/Bacteria: ").append(info.icd).append("\n");
        sb.append("Variant: ").append(info.cpt).append("\n\n");

        // Medicines section - combine system and manual medicines
        sb.append("Medicines:\n");
        // Add system medicines
        info.medications.forEach((m,d) -> sb.append(" - ").append(m).append(": ").append(d).append("\n"));
        // Add manual medicines integrated with system medicines
        if (patient.manualMedicines != null && !patient.manualMedicines.isEmpty()) {
            patient.manualMedicines.forEach(med -> sb.append(" - ").append(med).append("\n"));
        }

        // Tests section - combine system and manual tests
        sb.append("\nRecommended Tests:\n");
        // Add system tests
        info.tests.forEach(t -> sb.append(" - ").append(t).append("\n"));
        // Add manual tests integrated with system tests
        if (patient.manualTests != null && !patient.manualTests.isEmpty()) {
            patient.manualTests.forEach(test -> sb.append(" - ").append(test).append("\n"));
        }

        // Remedy/Advice section - combine system and manual remedies
        sb.append("\nAdvice/Remedy:\n").append(info.remedy);
        // Add manual remedy integrated with system remedy
        if (patient.manualRemedy != null && !patient.manualRemedy.isEmpty()) {
            sb.append("\n").append(patient.manualRemedy);
        }
        sb.append("\n");

        // Append Case Study if present
        if (patient.caseStudy != null) {
            sb.append("\n------------------------------\n");
            sb.append("Case Study / Report Info:\n");
            sb.append("Doctor: ").append(patient.caseStudy.doctorName).append("\n");
            sb.append("Hospital: ").append(patient.caseStudy.hospital).append("\n");
            sb.append("Contact: ").append(
                    (patient.caseStudy.contact == null || patient.caseStudy.contact.isEmpty()) ? "N/A" : patient.caseStudy.contact
            ).append("\n");
            sb.append("Report Date: ").append(patient.caseStudy.reportDate).append("\n");
            sb.append("Summary:\n").append(patient.caseStudy.summary).append("\n");
            sb.append("------------------------------\n");
        } else {
            sb.append("\n(No case study / inter-doctor report attached.)\n");
        }

        String prescriptionText = sb.toString();
        // Save into patient model so DB methods don't depend on UI controls being created
        patient.prescription = prescriptionText;
        prescriptionArea.setText(prescriptionText);
    }

    // Export prescription to PDF using PDFBox when available (reflection), else fallback to saving text
    private void exportPrescriptionToPDF() {
        String text = (prescriptionArea == null) ? null : prescriptionArea.getText();
        if (text == null || text.trim().isEmpty()) {
            if (prescriptionArea != null) prescriptionArea.setText("No prescription to export.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Prescription PDF");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Files", "*.pdf"));
        String baseName = (patient != null && patient.name != null && !patient.name.isEmpty()) ?
                "prescription_" + patient.name.replaceAll("\\s+", "_") + ".pdf" : "prescription.pdf";
        chooser.setInitialFileName(baseName);
        File file = chooser.showSaveDialog(stage);
        if (file == null) return;

        try {
            // Use PDFBox directly to create a formatted prescription PDF with structured layout
            try (PDDocument doc = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);

                float margin = 50f;
                float pageWidth = page.getMediaBox().getWidth();
                float pageHeight = page.getMediaBox().getHeight();
                PDPageContentStream cs = new PDPageContentStream(doc, page);

                // Header band
                cs.setNonStrokingColor(new java.awt.Color(79, 126, 245));
                cs.addRect(0, pageHeight - 100f, pageWidth, 100f);
                cs.fill();

                // Clinic / Doctor title in header (left) and small clinic info (right)
                cs.beginText();
                cs.setNonStrokingColor(java.awt.Color.white);
                cs.setFont(PDType1Font.HELVETICA_BOLD, 20);
                cs.newLineAtOffset(margin + 4, pageHeight - 62f);
                String clinicTitle = (patient != null && patient.caseStudy != null && patient.caseStudy.doctorName != null && !patient.caseStudy.doctorName.isEmpty())
                        ? patient.caseStudy.doctorName : "Dr. Doctor Name";
                cs.showText(clinicTitle);
                cs.endText();

                cs.beginText();
                cs.setNonStrokingColor(java.awt.Color.white);
                cs.setFont(PDType1Font.HELVETICA, 10);
                cs.newLineAtOffset(pageWidth - margin - 260, pageHeight - 60f);
                cs.showText("Clinic Name • 24 Dummy Street Area • +12-345 678 9012");
                cs.endText();

                // Small patient header details under the band (left)
                float startY = pageHeight - 120f;
                cs.beginText();
                cs.setNonStrokingColor(java.awt.Color.black);
                cs.setFont(PDType1Font.HELVETICA, 10);
                cs.newLineAtOffset(margin, startY);
                String pLine1 = "Patient: " + (patient != null ? patient.name : "") + "    Date: " + java.time.LocalDate.now();
                cs.showText(pLine1);
                cs.endText();

                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 10);
                cs.newLineAtOffset(margin, startY - 14f);
                String pLine2 = "Age: " + (patient != null ? patient.age : "") + "    Gender: " + (patient != null ? patient.gender : "") + (patient != null && patient.weight != null && !patient.weight.isEmpty() ? "    Weight: " + patient.weight + " kg" : "");
                cs.showText(pLine2);
                cs.endText();

                // Draw large Rx on the left and structured content on the right
                float rxX = margin;
                float rxY = startY - 90f;
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD, 88);
                cs.newLineAtOffset(rxX, rxY);
                cs.showText("Rx");
                cs.endText();

                // Parse prescription text into sections for nicer layout
                java.util.List<String> rawLines = java.util.Arrays.asList(text.split("\\r?\\n"));
                String patientName = extractLineValue(rawLines, "Patient Name:");
                String ageLine = extractLineValue(rawLines, "Age:");
                String tempLine = extractLineValue(rawLines, "Temperature:");
                String diagnosis = extractLineValue(rawLines, "Diagnosis:");
                String virus = extractLineValue(rawLines, "Virus/Bacteria:");
                String variant = extractLineValue(rawLines, "Variant:");

                java.util.List<String> medicines = extractSection(rawLines, "Medicines:", "Recommended Tests:");
                java.util.List<String> tests = extractSection(rawLines, "Recommended Tests:", "Advice/Remedy:");
                java.util.List<String> advice = extractSection(rawLines, "Advice/Remedy:", "(No case study");
                // case study is optional
                java.util.List<String> caseStudyLines = new java.util.ArrayList<>();
                int csIndex = indexOfPrefix(rawLines, "Case Study / Report Info:");
                if (csIndex >= 0) {
                    for (int i = csIndex; i < rawLines.size(); i++) caseStudyLines.add(rawLines.get(i));
                }

                float colX = margin + 120f;
                float cursorY = startY - 40f;
                float usableWidth = pageWidth - margin - colX - 20f;
                float leading = 14f;

                // Patient summary to the right of Rx
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD, 12);
                cs.newLineAtOffset(colX, cursorY);
                cs.showText("Patient Name: " + (patientName.isEmpty() ? (patient != null ? patient.name : "") : patientName));
                cs.endText();
                cursorY -= leading;

                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 11);
                cs.newLineAtOffset(colX, cursorY);
                String ag = ageLine.isEmpty() ? (patient != null ? ("Age: " + patient.age + ", Gender: " + patient.gender) : "") : ageLine;
                cs.showText(ag);
                cs.endText();
                cursorY -= leading;

                if (!tempLine.isEmpty()) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA, 11);
                    cs.newLineAtOffset(colX, cursorY);
                    cs.showText(tempLine + (patient != null && patient.bp != null && !patient.bp.isEmpty() ? ", BP: " + patient.bp : ""));
                    cs.endText();
                    cursorY -= leading * 1.2f;
                }

                // Diagnosis block
                if (diagnosis != null && !diagnosis.isEmpty()) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 13);
                    cs.newLineAtOffset(colX, cursorY);
                    cs.showText("Diagnosis: " + diagnosis);
                    cs.endText();
                    cursorY -= leading * 1.6f;
                }
                if (virus != null && !virus.isEmpty()) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA, 11);
                    cs.newLineAtOffset(colX, cursorY);
                    cs.showText("Virus/Bacteria: " + virus);
                    cs.endText();
                    cursorY -= leading;
                }
                if (variant != null && !variant.isEmpty()) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA, 11);
                    cs.newLineAtOffset(colX, cursorY);
                    cs.showText("Variant: " + variant);
                    cs.endText();
                    cursorY -= leading * 1.4f;
                }

                // Medicines
                if (!medicines.isEmpty()) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 12);
                    cs.newLineAtOffset(colX, cursorY);
                    cs.showText("Medicines:");
                    cs.endText();
                    cursorY -= leading;
                    for (String m : medicines) {
                        String line = m.trim();
                        if (line.startsWith("- ")) line = line.substring(2);
                        java.util.List<String> wrapped = wrapText(line, PDType1Font.HELVETICA, 11, usableWidth);
                        for (String wline : wrapped) {
                            cs.beginText();
                            cs.setFont(PDType1Font.HELVETICA, 11);
                            cs.newLineAtOffset(colX + 6f, cursorY);
                            cs.showText("- " + wline);
                            cs.endText();
                            cursorY -= leading;
                        }
                    }
                    cursorY -= leading * 0.4f;
                }

                // Tests
                if (!tests.isEmpty()) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 12);
                    cs.newLineAtOffset(colX, cursorY);
                    cs.showText("Recommended Tests:");
                    cs.endText();
                    cursorY -= leading;
                    for (String t : tests) {
                        String line = t.trim();
                        if (line.startsWith("- ")) line = line.substring(2);
                        java.util.List<String> wrapped = wrapText(line, PDType1Font.HELVETICA, 11, usableWidth);
                        for (String wline : wrapped) {
                            cs.beginText();
                            cs.setFont(PDType1Font.HELVETICA, 11);
                            cs.newLineAtOffset(colX + 6f, cursorY);
                            cs.showText("- " + wline);
                            cs.endText();
                            cursorY -= leading;
                        }
                    }
                    cursorY -= leading * 0.4f;
                }

                // Advice / Remedy
                if (!advice.isEmpty()) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 12);
                    cs.newLineAtOffset(colX, cursorY);
                    cs.showText("Advice/Remedy:");
                    cs.endText();
                    cursorY -= leading;
                    for (String a : advice) {
                        String line = a.trim();
                        java.util.List<String> wrapped = wrapText(line, PDType1Font.HELVETICA, 11, usableWidth);
                        for (String wline : wrapped) {
                            cs.beginText();
                            cs.setFont(PDType1Font.HELVETICA, 11);
                            cs.newLineAtOffset(colX + 6f, cursorY);
                            cs.showText(wline);
                            cs.endText();
                            cursorY -= leading;
                        }
                    }
                    cursorY -= leading * 0.4f;
                }

                // Case Study block if present
                if (!caseStudyLines.isEmpty()) {
                    cursorY -= leading * 0.2f;
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 11);
                    cs.newLineAtOffset(colX, cursorY);
                    cs.showText("Case Study / Report Info:");
                    cs.endText();
                    cursorY -= leading;
                    for (String cl : caseStudyLines) {
                        if (cl == null || cl.isBlank()) continue;
                        java.util.List<String> wrapped = wrapText(cl.trim(), PDType1Font.HELVETICA, 10, usableWidth);
                        for (String wline : wrapped) {
                            cs.beginText();
                            cs.setFont(PDType1Font.HELVETICA, 10);
                            cs.newLineAtOffset(colX + 6f, cursorY);
                            cs.showText(wline);
                            cs.endText();
                            cursorY -= leading * 0.95f;
                        }
                    }
                }

                // Signature line (right side)
                cs.setStrokingColor(java.awt.Color.darkGray);
                cs.moveTo(pageWidth - margin - 180f, 120f);
                cs.lineTo(pageWidth - margin, 120f);
                cs.stroke();

                // Footer clinic info (italic small)
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_OBLIQUE, 9);
                cs.newLineAtOffset(margin, 60f);
                cs.showText("Clinic Name    •    24 Dummy Street Area    •    +12-345 678 9012");
                cs.endText();

                cs.close();
                doc.save(file);
                doc.close();

                if (prescriptionArea != null) prescriptionArea.appendText("\n\nSaved PDF: " + file.getAbsolutePath());
            }
        } catch (IOException ioex) {
            // I/O error while creating PDF — fallback: save as text file
            try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                w.write(text);
                if (prescriptionArea != null) prescriptionArea.appendText("\n\nPDF export I/O error. Saved as text: " + file.getAbsolutePath());
            } catch (IOException inner) {
                inner.printStackTrace();
                if (prescriptionArea != null) prescriptionArea.appendText("\n\nFailed to save file: " + inner.getMessage());
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            if (prescriptionArea != null) prescriptionArea.appendText("\n\nFailed to export PDF: " + ex.getMessage());
        }
    }

    // ---------------- SQLite helper methods ----------------
    private void initDatabase() {
        try {
            String url = "jdbc:sqlite:medassistpro.db";
            conn = DriverManager.getConnection(url);
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS patients (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "name TEXT, age TEXT, gender TEXT, temp TEXT, bp TEXT, weight TEXT," +
                        "manualMedicines TEXT, manualTests TEXT, manualRemedy TEXT," +
                        "caseDoctor TEXT, caseHospital TEXT, caseContact TEXT, caseDate TEXT, caseSummary TEXT" +
                        ")");
            }

            // ensure prescription and weight columns exist
            boolean hasPrescription = false;
            boolean hasWeight = false;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA table_info(patients)")) {
                while (rs.next()) {
                    String colName = rs.getString("name");
                    if ("prescription".equalsIgnoreCase(colName)) {
                        hasPrescription = true;
                    }
                    if ("weight".equalsIgnoreCase(colName)) {
                        hasWeight = true;
                    }
                }
            }
            if (!hasPrescription) {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("ALTER TABLE patients ADD COLUMN prescription TEXT");
                } catch (SQLException ex) {
                    // ignore if alter fails
                }
            }
            if (!hasWeight) {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("ALTER TABLE patients ADD COLUMN weight TEXT");
                } catch (SQLException ex) {
                    // ignore if alter fails
                }
            }

            // create users table for login
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS users (" +
                        "username TEXT PRIMARY KEY, " +
                        "passwordHash TEXT NOT NULL" +
                        ")");
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    // load users from DB into in-memory map
    private void loadUsersFromDB() {
        if (conn == null) return;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT username, passwordHash FROM users")) {
            while (rs.next()) {
                users.put(rs.getString("username"), rs.getString("passwordHash"));
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    // save user to DB (insert or replace)
    private boolean saveUserToDB(String username, String passwordHash) {
        if (conn == null) return false;
        String sql = "INSERT OR REPLACE INTO users(username, passwordHash) VALUES(?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.executeUpdate();
            return true;
        } catch (SQLException ex) {
            ex.printStackTrace();
            return false;
        }
    }

    // simple SHA-256 hash for passwords
    private String hashPassword(String password) {
        if (password == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            // fallback to plain (very unlikely)
            return password;
        }
    }

    // Update the saveOrUpdatePatientInDB method
    private void saveOrUpdatePatientInDB(Patient p) {
        if (p == null) return;
        try {
            if (conn == null || conn.isClosed()) {
                // try to re-open connection
                String url = "jdbc:sqlite:medassistpro.db";
                conn = DriverManager.getConnection(url);
            }

            if (p.id <= 0) {
                // insert (include weight)
                String sql = "INSERT INTO patients (name, age, gender, temp, bp, weight, manualMedicines, manualTests, manualRemedy, caseDoctor, caseHospital, caseContact, caseDate, caseSummary, prescription) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, p.name);
                    ps.setString(2, p.age);
                    ps.setString(3, p.gender);
                    ps.setString(4, p.temp);
                    ps.setString(5, p.bp);
                    ps.setString(6, p.weight);
                    ps.setString(7, listToString(p.manualMedicines));
                    ps.setString(8, listToString(p.manualTests));
                    ps.setString(9, p.manualRemedy);
                    if (p.caseStudy != null) {
                        ps.setString(10, p.caseStudy.doctorName);
                        ps.setString(11, p.caseStudy.hospital);
                        ps.setString(12, p.caseStudy.contact);
                        ps.setString(13, p.caseStudy.reportDate.toString());
                        ps.setString(14, p.caseStudy.summary);
                    } else {
                        ps.setString(10, null);
                        ps.setString(11, null);
                        ps.setString(12, null);
                        ps.setString(13, null);
                        ps.setString(14, null);
                    }
                    // Use prescription stored in patient model (UI may not be initialized when saving)
                    ps.setString(15, p.prescription);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) {
                            p.id = rs.getInt(1);
                        }
                    }
                }
            } else {
                // update (include weight)
                String sql = "UPDATE patients SET name=?, age=?, gender=?, temp=?, bp=?, weight=?, manualMedicines=?, manualTests=?, manualRemedy=?, caseDoctor=?, caseHospital=?, caseContact=?, caseDate=?, caseSummary=?, prescription=? WHERE id=?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, p.name);
                    ps.setString(2, p.age);
                    ps.setString(3, p.gender);
                    ps.setString(4, p.temp);
                    ps.setString(5, p.bp);
                    ps.setString(6, p.weight);
                    ps.setString(7, listToString(p.manualMedicines));
                    ps.setString(8, listToString(p.manualTests));
                    ps.setString(9, p.manualRemedy);
                    if (p.caseStudy != null) {
                        ps.setString(10, p.caseStudy.doctorName);
                        ps.setString(11, p.caseStudy.hospital);
                        ps.setString(12, p.caseStudy.contact);
                        ps.setString(13, p.caseStudy.reportDate.toString());
                        ps.setString(14, p.caseStudy.summary);
                    } else {
                        ps.setString(10, null);
                        ps.setString(11, null);
                        ps.setString(12, null);
                        ps.setString(13, null);
                        ps.setString(14, null);
                    }
                    // Update prescription from model
                    ps.setString(15, p.prescription);
                    ps.setInt(16, p.id);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    private String listToString(ObservableList<String> list) {
        if (list == null || list.isEmpty()) return null;
        // use ";;" as separator to reduce collisions
        return String.join(";;", list);
    }

    private ObservableList<String> stringToList(String s) {
        if (s == null || s.isEmpty()) return FXCollections.observableArrayList();
        String[] parts = s.split(";;");
        return FXCollections.observableArrayList(parts);
    }

    private void openPatientDetailsStage() {
        Stage dlg = new Stage();
        dlg.initOwner(stage);
        dlg.initModality(Modality.APPLICATION_MODAL);
        dlg.setTitle("Stored Patients");

        VBox root = new VBox(8);
        root.setPadding(new Insets(10));
        // dialog background via CSS
        root.getStyleClass().add("dialog-bg");

        ListView<String> listView = new ListView<>();
        TextArea details = new TextArea();
        details.setEditable(false);
        details.setWrapText(true);
        details.setPrefHeight(300);
        details.getStyleClass().add("detail-area");

        // load patients
        ObservableList<Integer> ids = FXCollections.observableArrayList();
        ObservableList<String> names = FXCollections.observableArrayList();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name FROM patients ORDER BY id DESC")) {
            while (rs.next()) {
                int id = rs.getInt("id");
                String n = rs.getString("name");
                ids.add(id);
                names.add(id + " - " + n);
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }

        listView.setItems(names);
        listView.getSelectionModel().selectedIndexProperty().addListener((obs, oldI, newI) -> {
            int i = newI.intValue();
            if (i >= 0 && i < ids.size()) {
                int selId = ids.get(i);
                // load details
                try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM patients WHERE id=?")) {
                    ps.setInt(1, selId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            StringBuilder sb = new StringBuilder();
                            sb.append("Name: ").append(rs.getString("name")).append("\n");
                            sb.append("Age: ").append(rs.getString("age")).append(", Gender: ").append(rs.getString("gender")).append("\n");
                            sb.append("Temp: ").append(rs.getString("temp")).append("°F, BP: ").append(rs.getString("bp"));
                            String wt = rs.getString("weight");
                            if (wt != null && !wt.isEmpty()) {
                                sb.append(", Weight: ").append(wt).append(" kg");
                            }
                            sb.append("\n\n");
                            sb.append("Manual Medicines:\n");
                            sb.append(convertStringListDisplay(rs.getString("manualMedicines"))).append("\n\n");
                            sb.append("Manual Tests:\n");
                            sb.append(convertStringListDisplay(rs.getString("manualTests"))).append("\n\n");
                            sb.append("Manual Remedy:\n").append(rs.getString("manualRemedy")).append("\n\n");
                            sb.append("Case Study:\n");
                            sb.append("Doctor: ").append(rs.getString("caseDoctor")).append("\n");
                            sb.append("Hospital: ").append(rs.getString("caseHospital")).append("\n");
                            sb.append("Contact: ").append(rs.getString("caseContact")).append("\n");
                            sb.append("Date: ").append(rs.getString("caseDate")).append("\n");
                            sb.append("Summary: ").append(rs.getString("caseSummary")).append("\n\n");
                            sb.append("Saved Prescription:\n");
                            String pres = rs.getString("prescription");
                            sb.append((pres == null || pres.isEmpty()) ? "(none)" : pres).append("\n");
                            details.setText(sb.toString());
                        }
                    }
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }
        });

        Button closeBtn = new Button("Close");
        closeBtn.getStyleClass().add("danger-button");
        closeBtn.setOnAction(e -> dlg.close());

        root.getChildren().addAll(new Label("Select a patient to view details:"), listView, details, closeBtn);
        dlg.setScene(new Scene(root, 600, 500));
        dlg.show();
    }

    private String convertStringListDisplay(String s) {
        if (s == null || s.isEmpty()) return "(none)";
        return s.replace(";;", "\n - ");
    }

    // ---------------- DATA MODELS ----------------
    private static class DiseaseInfo {
        String icd, cpt, remedy;
        List<String> questions, tests;
        Map<String,String> medications;
        DiseaseInfo(String icd, String cpt, String remedy, List<String> questions,
                    Map<String,String> meds, List<String> tests) {
            this.icd=icd; this.cpt=cpt; this.remedy=remedy;
            this.questions=questions; this.medications=meds; this.tests=tests;
        }
    }

    private static class CaseStudy {
        String doctorName;
        String hospital;
        String contact;
        LocalDate reportDate;
        String summary;
        CaseStudy(String d, String h, String c, LocalDate rd, String s) {
            this.doctorName = d;
            this.hospital = h;
            this.contact = c;
            this.reportDate = rd;
            this.summary = s;
        }
    }

    private static class Patient {
        int id = -1; // new field to track DB id
        String name="", age="", gender="", temp="", bp="";
        String weight="";
        CaseStudy caseStudy = null;
        ObservableList<String> manualMedicines;
        ObservableList<String> manualTests;
        String manualRemedy;
        // new field to hold generated/saved prescription text
        String prescription;
        Patient() {}
        Patient(String n, String a, String g, String t, String b, String w) {
            name=n; age=a; gender=g; temp=t; bp=b; weight=w;
        }
    }

    @Override
    public void stop() throws Exception {
        super.stop();
        if (conn != null && !conn.isClosed()) conn.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}