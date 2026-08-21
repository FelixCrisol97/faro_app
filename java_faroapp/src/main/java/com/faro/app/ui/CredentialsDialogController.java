package com.faro.app.ui;

import com.faro.app.data.CredentialStore;

import javafx.fxml.FXML;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

/** Controlador del diálogo "Credenciales por defecto" — ver {@link CredentialStore#setDefault}. */
public class CredentialsDialogController {

    @FXML private TextField userField;
    @FXML private PasswordField passwordField;

    private Stage stage;
    private CredentialStore credentials;
    private boolean saved;

    void attachStage(Stage stage) {
        this.stage = stage;
    }

    void attachCredentialStore(CredentialStore credentials) {
        this.credentials = credentials;
        credentials.getDefault().ifPresent(current -> {
            userField.setText(current.user());
            passwordField.setText(current.password());
        });
    }

    boolean wasSaved() {
        return saved;
    }

    @FXML
    private void onCancel() {
        saved = false;
        stage.close();
    }

    @FXML
    private void onSave() {
        credentials.setDefault(userField.getText(), passwordField.getText());
        saved = true;
        stage.close();
    }
}
