package com.smatworld.iniencrypt;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;
import com.smatworld.iniencrypt.data.security.utils.SecurityUtil;
import com.smatworld.iniencrypt.databinding.FragmentAlgorithmDialogBinding;
import com.smatworld.iniencrypt.databinding.FragmentMainBinding;
import com.smatworld.iniencrypt.databinding.FragmentMainDialogBinding;
import com.smatworld.iniencrypt.di.AppContainer;
import com.smatworld.iniencrypt.di.IniEncryptApp;
import com.smatworld.iniencrypt.models.Algorithm;
import com.smatworld.iniencrypt.models.FileData;
import com.smatworld.iniencrypt.models.TaskData;
import com.smatworld.iniencrypt.models.TaskStatus;
import com.smatworld.iniencrypt.presentation.FileViewModel;
import com.smatworld.iniencrypt.utils.Constants;
import com.smatworld.iniencrypt.utils.FileUtil;
import com.smatworld.iniencrypt.utils.StringUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.Key;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

public class MainFragment extends Fragment implements View.OnClickListener {

    private FragmentMainBinding mBinding;
    public static final int FILE_REQUEST_CODE = 4;
    private FileViewModel mFileViewModel;
    private BottomDialog mBottomDialog;

    private Observer<TaskData<InputStream>> mEncryptObserver;
    private Observer<TaskData<InputStream>> mDecryptObserver;
    private Observer<TaskData<Key>> mDHKeyExchangeObserver;
    private Observer<TaskData<InputStream>> mRSAKeyObserver;
    private LiveData<TaskData<InputStream>> mDecryptLiveData;
    private LiveData<TaskData<InputStream>> mEncryptLiveData;
    private LiveData<TaskData<InputStream>> mRSALiveData;
    private LiveData<TaskData<Key>> mDHLiveData;

    public MainFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppContainer appContainer = ((IniEncryptApp) requireActivity().getApplication()).appContainer;
        mFileViewModel = new ViewModelProvider(requireActivity(), appContainer.fileViewModelFactory).get(FileViewModel.class);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        mBinding = FragmentMainBinding.inflate(inflater, container, false);
        mBinding.setLifecycleOwner(requireActivity());
        mBinding.setViewModel(mFileViewModel);
        return mBinding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        closeAppOnBackPressed();
        mBinding.chooseButton.setOnClickListener(v -> {
            final boolean isSuccessful = FileUtil.uploadFileFromStorage(this);
            if (!isSuccessful) displaySnackBar(requireActivity().getString(R.string.no_app_found));
        });

        for (int i = 0; i < mBinding.asymmetricChipGroup.getChildCount(); i++) {
            mBinding.asymmetricChipGroup.getChildAt(i).setOnClickListener(this);
            mBinding.symmetricChipGroup.getChildAt(i).setOnClickListener(this);
        }

        mBinding.refreshButton.setOnClickListener(v -> refreshWorkSpace());
        mBinding.encryptButton.setOnClickListener(v -> displayInputDialog());
        mBinding.decryptButton.setOnClickListener(v -> {
            final FileData fileData = mFileViewModel.getFileData().getValue();
            if (fileData != null && fileData.getEncryptedFile() != null)
                startDecryption(fileData.getKey());
            else displaySnackBar("You have to encrypt a file first.");
        });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (data != null) {
                ClipData clipData = data.getClipData();
                // occurs when user selects multiple files
                if (clipData == null) { // occurs when user selects only a file
                    String previewData = "";
                    Bitmap bitmap = null;
                    final Uri imageUri = data.getData();
                    final File file = FileUtil.getFileFromUri(this, imageUri);
                    final String fileName = FileUtil.getQueryName(imageUri, this);
                    String fileExtension = FileUtil.getFileExtension(fileName);
                    boolean isImage;
                    if (fileExtension.toLowerCase().startsWith(".t")) {
                        isImage = false;
                        previewData = FileUtil.getTextFromFile(file);
                        // FIXME: 27/03/2021 Remove this
                        FileUtil.saveFileToStorage(requireContext(), SecurityUtil.getInputStreamFromFile(file), Constants.PLAIN_TEXT_FILE_NAME + fileExtension);
                        // display default image
                    } else {
                        isImage = true;
                        try {
                            bitmap = FileUtil.getBitmapFromUri(imageUri, this);
                            // FIXME: 27/03/2021 Remove this
                            FileUtil.saveFileToStorage(requireContext(), SecurityUtil.getInputStreamFromFile(file), Constants.PLAIN_IMAGE_NAME + fileExtension);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }

                    FileData fileData = new FileData(bitmap, file, fileName, file.length(), fileExtension, previewData, isImage);
                    mFileViewModel.setFileData(fileData);
                    mBinding.setViewModel(mFileViewModel);
                    changeViewState();
                } else displaySnackBar("You can only upload a single file.");
            }
        }
    }

    @Override
    public void onClick(View v) {
        final Chip chip = ((Chip) v);
        final int buttonId = chip.getId();
        final boolean isChecked = chip.isChecked();
        String selectedEncryption = "";

        if (buttonId == R.id.aes_chip && isChecked) {
            selectedEncryption = getString(R.string.aes);
            setState(mBinding.asymmetricChipGroup);
            mFileViewModel.setAlgorithm(Algorithm.AES);
        } else if (buttonId == R.id.des_chip && isChecked) {
            selectedEncryption = getString(R.string._3des);
            setState(mBinding.asymmetricChipGroup);
            mFileViewModel.setAlgorithm(Algorithm.TRIPLE_DES);
        } else if (buttonId == R.id.rsa_chip && isChecked) {
            selectedEncryption = getString(R.string.rsa);
            setState(mBinding.symmetricChipGroup);
            mFileViewModel.setAlgorithm(Algorithm.RSA);
        } else if (buttonId == R.id.dh_chip && isChecked) {
            selectedEncryption = getString(R.string.diffie);
            setState(mBinding.symmetricChipGroup);
            mFileViewModel.setAlgorithm(Algorithm.DIFFIE_HELLMAN);
        }
        final MaterialButton selectedEncryptButton = mBinding.selectedEncryptButton;
        selectedEncryptButton.setText(selectedEncryption);
        if (selectedEncryption.isEmpty()) {
            selectedEncryptButton.setVisibility(View.GONE);
            mFileViewModel.setAlgorithmSelected(false);
            mBinding.chooseButton.setEnabled(false);
            disableCryptographyButtons();
        } else {
            selectedEncryptButton.setVisibility(View.VISIBLE);
            mFileViewModel.setAlgorithmSelected(true);
            mBinding.chooseButton.setEnabled(true);
            changeViewState();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mBinding = null;
    }

    private void closeAppOnBackPressed() {
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                requireActivity().finish();
            }
        });
    }

    private void startDecryption(String key) {
        showBottomDialog(String.format(Locale.getDefault(), "%s decryption in progress...", mFileViewModel.getAlgorithm().getAlgorithm()), R.drawable.ic_no_encryption_blue);
        final FileData fileData = mFileViewModel.getFileData().getValue();
        String encryptedFileName = "";
        if (Objects.requireNonNull(fileData).isImage())
            encryptedFileName = Constants.ENCRYPTED_IMAGE_FILE_NAME;
        else encryptedFileName = Constants.ENCRYPTED_TEXT_FILE_NAME;
        switch (mFileViewModel.getAlgorithm()) {
            case AES:
                mDecryptLiveData = mFileViewModel.decryptAES(SecurityUtil.getInputStreamFromFile(FileUtil.getFile(requireContext(), encryptedFileName)), key);
                mDecryptObserver = decryptTaskObserver(fileData);
                mDecryptLiveData.observe(getViewLifecycleOwner(), mDecryptObserver);
                break;
            case TRIPLE_DES:
                mDecryptLiveData = mFileViewModel.decryptTripleDES(SecurityUtil.getInputStreamFromFile(FileUtil.getFile(requireContext(), encryptedFileName)), key);
                mDecryptObserver = decryptTaskObserver(fileData);
                mDecryptLiveData.observe(getViewLifecycleOwner(), mDecryptObserver);
                break;
            case RSA:
                final int keyLength = Integer.parseInt(key);
                if (StringUtil.canUseRSA(Objects.requireNonNull(fileData).getFileSize(), keyLength)) {
                    // file size is suitable for RSA
                    mDecryptLiveData = mFileViewModel.decryptRSA(SecurityUtil.getInputStreamFromFile(FileUtil.getFile(requireContext(), encryptedFileName)));
                    mDecryptObserver = decryptTaskObserver(fileData);
                    mDecryptLiveData.observe(getViewLifecycleOwner(), mDecryptObserver);
                } else if (FileUtil.fileExists(requireContext(), Constants.RSA_ENCRYPTED_KEY_FILE_NAME)) {
                    // use the RSA-encrypted DH Secret Key to decrypt the data
                    decryptDH(mFileViewModel.getSelectedSymmetricAlgorithm());
                } else displaySnackBar("You have to encrypt with RSA first.");
                break;
            case DIFFIE_HELLMAN:
                if (fileData.isSecretKeyAvailable())
                    decryptDH(mFileViewModel.getSelectedSymmetricAlgorithm());
                else displaySnackBar(getString(R.string.error_message));
                break;
            default:
                throw new IllegalArgumentException(getString(R.string.invalid_algorithm) + mFileViewModel.getAlgorithm());
        }
    }

    private void startEncryption(String key) {
        showBottomDialog(String.format(Locale.getDefault(), "%s encryption in progress...", mFileViewModel.getAlgorithm().getAlgorithm()), R.drawable.ic_encryption_blue);
        final FileData fileData = mFileViewModel.getFileData().getValue();
        switch (mFileViewModel.getAlgorithm()) {
            case AES:
                mEncryptLiveData = mFileViewModel.encryptAES(SecurityUtil.getInputStreamFromFile(Objects.requireNonNull(fileData).getFile()), key);
                mEncryptObserver = encryptTaskObserver(fileData);
                mEncryptLiveData.observe(getViewLifecycleOwner(), mEncryptObserver);
                break;
            case TRIPLE_DES:
                mEncryptLiveData = mFileViewModel.encryptTripleDES(SecurityUtil.getInputStreamFromFile(Objects.requireNonNull(fileData).getFile()), key);
                mEncryptObserver = encryptTaskObserver(fileData);
                mEncryptLiveData.observe(getViewLifecycleOwner(), mEncryptObserver);
                break;
            case RSA:
                final int keyLength = Integer.parseInt(key);
                if (StringUtil.canUseRSA(Objects.requireNonNull(fileData).getFileSize(), keyLength)) {
                    // file size is suitable for RSA
                    mEncryptLiveData = mFileViewModel.encryptRSA(SecurityUtil.getInputStreamFromFile(Objects.requireNonNull(fileData).getFile()), keyLength);
                    mEncryptObserver = encryptTaskObserver(fileData);
                    mEncryptLiveData.observe(getViewLifecycleOwner(), mEncryptObserver);
                } else {
                    // encrypt the DH-generated Secret Key with RSA and
                    // use the RSA-encrypted key to encrypt the data
                    mFileViewModel.setSelectedASymmetricAlgorithm(Algorithm.RSA);
                    // show option to select a Symmetric Algorithm
                    showAlgorithmSelectionDialog();
                }
                break;
            case DIFFIE_HELLMAN:
                // show option to select a Symmetric Algorithm
                mFileViewModel.setSelectedASymmetricAlgorithm(Algorithm.DIFFIE_HELLMAN);
                showAlgorithmSelectionDialog();
                break;
            default:
                throw new IllegalArgumentException(getString(R.string.invalid_algorithm) + mFileViewModel.getAlgorithm());
        }
    }

    private Observer<TaskData<InputStream>> encryptTaskObserver(FileData fileData) {
        return taskData -> {
            if (taskData.getTaskStatus() == TaskStatus.SUCCESS) {
                dismissBottomDialog();
                displaySnackBar(taskData.getSuccessMessage());
                final InputStream encryptedStream = taskData.getData();
                String encodedFile;
                // save encrypted data to File
                if (fileData.isImage()) {
                    String encryptedImageFileName = Constants.ENCRYPTED_IMAGE_FILE_NAME;
                    FileUtil.saveFileToStorage(requireContext(), encryptedStream, encryptedImageFileName);
                    encodedFile = FileUtil.getEncodedFile(FileUtil.getFile(requireContext(), encryptedImageFileName));
                    mBinding.encryptedTv.setText(encodedFile);
                    mBinding.previewImage.setImageResource(R.drawable.image_encrypt);
                } else {
                    final String encryptedTextFileName = Constants.ENCRYPTED_TEXT_FILE_NAME;
                    FileUtil.saveFileToStorage(requireContext(), encryptedStream, encryptedTextFileName);
                    encodedFile = FileUtil.getEncodedFile(FileUtil.getFile(requireContext(), encryptedTextFileName));
                    mBinding.encryptedTv.setText(encodedFile);
                }

                final long encryptionTime = taskData.getEndTime() - taskData.getStartTime();
                mBinding.encryptionTimeTv.setText(getString(R.string.encryption_time, String.valueOf(encryptionTime)));
                mBinding.encryptionTimeTv.setVisibility(View.VISIBLE);
                // update ViewModel
                fileData.setEncryptedFile(encodedFile);
                fileData.setEncryptionTime(encryptionTime);
                fileData.setStreamAvailable(true);
                mBinding.decryptButton.setEnabled(true);
                removeObservers();
            } else if (taskData.getTaskStatus() == TaskStatus.FAILED) {
                dismissBottomDialog();
                displaySnackBar(taskData.getErrorMessage());
                fileData.setStreamAvailable(false);
                removeObservers();
            }
        };
    }

    private Observer<TaskData<InputStream>> decryptTaskObserver(FileData fileData) {
        return data -> {
            if (data.getTaskStatus() == TaskStatus.SUCCESS) {
                dismissBottomDialog();
                displaySnackBar(data.getSuccessMessage());
                final InputStream decryptedStream = data.getData();
                String decodedText = "";
                String decryptedImagePath = "";

                // save encrypted data to File
                if (fileData.isImage()) {
                    final String filename = Constants.DECRYPTED_IMAGE_FILE_NAME + fileData.getFileExtension();
                    FileUtil.saveFileToStorage(requireContext(), decryptedStream, filename);
                    decryptedImagePath = FileUtil.getFile(requireContext(), filename).getPath();
                    mBinding.previewImage.setImageBitmap(BitmapFactory.decodeFile(decryptedImagePath));
                } else {
                    final String filename = Constants.DECRYPTED_TEXT_FILE_NAME + fileData.getFileExtension();
                    FileUtil.saveFileToStorage(requireContext(), decryptedStream, filename);
                    decodedText = FileUtil.getTextFromFile(FileUtil.getFile(requireContext(), filename));
                    mBinding.plainTv.setText(decodedText);
                }

                final long decryptionTime = data.getEndTime() - data.getStartTime();
                mBinding.decryptionTimeTv.setText(getString(R.string.decryption_time, String.valueOf(decryptionTime)));
                mBinding.decryptionTimeTv.setVisibility(View.VISIBLE);
                // update ViewModel
                fileData.setDecryptedText(decodedText);
                fileData.setDecryptedImagePath(decryptedImagePath);
                if (!decryptedImagePath.isEmpty())
                    fileData.setBitmap(BitmapFactory.decodeFile(decryptedImagePath));
                fileData.setDecryptionTime(decryptionTime);
                fileData.setStreamAvailable(true);
                removeObservers();
            } else if (data.getTaskStatus() == TaskStatus.FAILED) {
                dismissBottomDialog();
                displaySnackBar(data.getErrorMessage());
                removeObservers();
            }
        };
    }

    private void removeObservers() {
        if (mDecryptObserver != null && mDecryptLiveData != null)
            if (mDecryptLiveData.hasActiveObservers())
                mDecryptLiveData.removeObserver(mDecryptObserver);
        if (mEncryptObserver != null && mEncryptLiveData != null)
            if (mEncryptLiveData.hasActiveObservers())
                mEncryptLiveData.removeObserver(mEncryptObserver);
        if (mDHKeyExchangeObserver != null && mDHLiveData != null)
            if (mDHLiveData.hasActiveObservers())
                mDHLiveData.removeObserver(mDHKeyExchangeObserver);
        if (mRSAKeyObserver != null && mRSALiveData != null)
            if (mRSALiveData.hasActiveObservers())
                mRSALiveData.removeObserver(mRSAKeyObserver);
    }

    private void setState(ChipGroup chipGroup) {
        for (int i = 0; i < chipGroup.getChildCount(); i++)
            ((Chip) chipGroup.getChildAt(i)).setChecked(false);
    }

    private void disableCryptographyButtons() {
        mBinding.encryptButton.setEnabled(false);
        mBinding.decryptButton.setEnabled(false);
    }

    private void displaySnackBar(String message) {
        Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG).show();
    }

    private void dismissBottomDialog() {
        if (mBottomDialog != null) mBottomDialog.dismiss();
    }

    private void refreshWorkSpace() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_title)
                .setMessage(R.string.dialog_message)
                .setIcon(R.drawable.ic_baseline_warning_24)
                .setPositiveButton(R.string.dialog_yes, (dialog, which) -> {
                    disableCryptographyButtons();
                    mBinding.chooseButton.setEnabled(false);
                    mBinding.previewTv.setText("");
                    mBinding.encryptedTv.setText("");
                    mBinding.plainTv.setText("");
                    mBinding.fileNameTv.setText("");
                    mBinding.previewImage.setImageResource(R.drawable.file_key);
                    mBinding.keyTv.setText(getString(R.string.key_text_view));
                    mBinding.selectedEncryptButton.setVisibility(View.GONE);
                    setState(mBinding.asymmetricChipGroup);
                    setState(mBinding.symmetricChipGroup);
                    mFileViewModel.setAlgorithmSelected(false);
                    mFileViewModel.setFileData(null);
                    mFileViewModel.setAlgorithm(null);
                    // FIXME: 30/03/2021 delete cached files and files in FileDir
                })
                .setNegativeButton(R.string.dialog_no, (dialog, which) -> dialog.cancel())
                .show();
    }

    private void showBottomDialog(String title, int resId) {
        mBottomDialog = BottomDialog.newInstance(String.format(Locale.getDefault(), title, mFileViewModel.getAlgorithm().getAlgorithm()), resId);
        mBottomDialog.setCancelable(false);
        mBottomDialog.show(requireParentFragment().getParentFragmentManager(), title);
    }

    private void showAlgorithmSelectionDialog() {
        dismissBottomDialog();
        FragmentAlgorithmDialogBinding binding = FragmentAlgorithmDialogBinding.inflate(LayoutInflater.from(requireContext()));
        binding.setAlgorithmList(Arrays.asList(Algorithm.AES, Algorithm.TRIPLE_DES));
        final BottomSheetDialog sheetDialog = new BottomSheetDialog(requireContext());
        sheetDialog.setContentView(binding.getRoot());
        final FileData fileData = mFileViewModel.getFileData().getValue();
        sheetDialog.show();
        binding.alg1.setOnClickListener(v -> {
            sheetDialog.dismiss();
            mDHLiveData = mFileViewModel.initDHKeyExchange(Integer.parseInt(Objects.requireNonNull(fileData).getKey()), Algorithm.AES);
            mDHKeyExchangeObserver = DHTaskObserver(Algorithm.AES);
            mDHLiveData.observe(getViewLifecycleOwner(), mDHKeyExchangeObserver);
        });
        binding.alg2.setOnClickListener(v -> {
            sheetDialog.dismiss();
            mDHLiveData = mFileViewModel.initDHKeyExchange(Integer.parseInt(Objects.requireNonNull(fileData).getKey()), Algorithm.TRIPLE_DES);
            mDHKeyExchangeObserver = DHTaskObserver(Algorithm.TRIPLE_DES);
            mDHLiveData.observe(getViewLifecycleOwner(), mDHKeyExchangeObserver);
        });
    }

    private Observer<TaskData<Key>> DHTaskObserver(Algorithm algorithm) {
        return keyTaskData -> {
            if (keyTaskData.getTaskStatus() == TaskStatus.SUCCESS) {
                displaySnackBar(keyTaskData.getSuccessMessage());
                final Key key = keyTaskData.getData();
                // save key to Storage
                FileUtil.saveFileToStorage(requireContext(), key.getEncoded(), Constants.DH_ENCRYPTED_KEY_FILE_NAME);
                // update ViewModel
                Objects.requireNonNull(mFileViewModel.getFileData().getValue()).setSecretKeyAvailable(true);
                mFileViewModel.setSelectedSymmetricAlgorithm(algorithm);
                removeObservers();
                // initiate with RSA
                if (mFileViewModel.getSelectedASymmetricAlgorithm() == Algorithm.RSA)
                    encryptRSA();
                else encryptDH(algorithm);
            } else if (keyTaskData.getTaskStatus() == TaskStatus.FAILED) {
                dismissBottomDialog();
                displaySnackBar(keyTaskData.getErrorMessage());
                // update ViewModel
                Objects.requireNonNull(mFileViewModel.getFileData().getValue()).setSecretKeyAvailable(false);
                removeObservers();
            }
        };
    }

    private Observer<TaskData<InputStream>> getRSAKeyObserver() {
        return taskData -> {
            if (taskData.getTaskStatus() == TaskStatus.SUCCESS) {
                dismissBottomDialog();
                displaySnackBar(taskData.getSuccessMessage());
                final InputStream encryptedStream = taskData.getData();
                // save encrypted DH SecretKey to File
                String encryptedFileName = Constants.RSA_ENCRYPTED_KEY_FILE_NAME;
                FileUtil.saveFileToStorage(requireContext(), encryptedStream, encryptedFileName);
                removeObservers();
                // initiate encryption of data with the RSA-encrypted DH SecretKey
                final Algorithm symmetricAlgorithm = mFileViewModel.getSelectedSymmetricAlgorithm();
                final Key secretKeyFromFile = FileUtil.getSecretKeyFromFile(FileUtil.getFile(requireContext(), encryptedFileName), symmetricAlgorithm);
                initSymmetricEncryption(symmetricAlgorithm, secretKeyFromFile);
            } else if (taskData.getTaskStatus() == TaskStatus.FAILED) {
                dismissBottomDialog();
                displaySnackBar(taskData.getErrorMessage());
                removeObservers();
            }
        };
    }

    // RSA encrypts DH SecretKey
    private void encryptRSA() {
        final FileData fileData = mFileViewModel.getFileData().getValue();
        mRSALiveData = mFileViewModel.encryptRSA(SecurityUtil.getInputStreamFromFile(FileUtil.getFile(requireContext(), Constants.DH_ENCRYPTED_KEY_FILE_NAME)), Integer.parseInt(Objects.requireNonNull(fileData).getKey()));
        mRSAKeyObserver = getRSAKeyObserver();
        mRSALiveData.observe(getViewLifecycleOwner(), mRSAKeyObserver);
    }

    // initiate Encryption with DH
    private void encryptDH(Algorithm symmetricAlgorithm) {
        final Key secretKeyFromFile = FileUtil.getSecretKeyFromFile(FileUtil.getFile(requireContext(), Constants.DH_ENCRYPTED_KEY_FILE_NAME), symmetricAlgorithm);
        initSymmetricEncryption(symmetricAlgorithm, secretKeyFromFile);

    }

    private void initSymmetricEncryption(Algorithm symmetricAlgorithm, Key secretKeyFromFile) {
        final FileData fileData = mFileViewModel.getFileData().getValue();
        final InputStream inputStreamFromFile = SecurityUtil.getInputStreamFromFile(Objects.requireNonNull(fileData).getFile());
        switch (symmetricAlgorithm) {
            case AES:
                mEncryptLiveData = mFileViewModel.encryptAES(inputStreamFromFile, secretKeyFromFile);
                mEncryptObserver = encryptTaskObserver(fileData);
                mEncryptLiveData.observe(getViewLifecycleOwner(), mEncryptObserver);
                break;
            case TRIPLE_DES:
                mEncryptLiveData = mFileViewModel.encryptTripleDES(inputStreamFromFile, secretKeyFromFile);
                mEncryptObserver = encryptTaskObserver(fileData);
                mEncryptLiveData.observe(getViewLifecycleOwner(), mEncryptObserver);
                break;
            default:
                throw new IllegalArgumentException(getString(R.string.invalid_algorithm) + mFileViewModel.getAlgorithm());
        }
    }

    private void decryptDH(Algorithm symmetricAlgorithm) {
        final FileData fileData = mFileViewModel.getFileData().getValue();
        final String encryptedFileName;
        final Key secretKeyFromFile;
        if (Objects.requireNonNull(fileData).isImage())
            encryptedFileName = Constants.ENCRYPTED_IMAGE_FILE_NAME;
        else encryptedFileName = Constants.ENCRYPTED_TEXT_FILE_NAME;
        final InputStream inputStreamFromFile = SecurityUtil.getInputStreamFromFile(FileUtil.getFile(requireContext(), encryptedFileName));
        if (mFileViewModel.getSelectedASymmetricAlgorithm() == Algorithm.RSA)
            secretKeyFromFile = FileUtil.getSecretKeyFromFile(FileUtil.getFile(requireContext(), Constants.RSA_ENCRYPTED_KEY_FILE_NAME), symmetricAlgorithm);
        else
            secretKeyFromFile = FileUtil.getSecretKeyFromFile(FileUtil.getFile(requireContext(), Constants.DH_ENCRYPTED_KEY_FILE_NAME), symmetricAlgorithm);
        switch (symmetricAlgorithm) {
            case AES:
                mDecryptLiveData = mFileViewModel.decryptAES(inputStreamFromFile, secretKeyFromFile);
                mDecryptObserver = decryptTaskObserver(fileData);
                mDecryptLiveData.observe(getViewLifecycleOwner(), mDecryptObserver);
                break;
            case TRIPLE_DES:
                mDecryptLiveData = mFileViewModel.decryptTripleDES(inputStreamFromFile, secretKeyFromFile);
                mDecryptObserver = decryptTaskObserver(fileData);
                mDecryptLiveData.observe(getViewLifecycleOwner(), mDecryptObserver);
                break;
            default:
                throw new IllegalArgumentException(getString(R.string.invalid_algorithm) + mFileViewModel.getAlgorithm());
        }

    }

    private void displayInputDialog() {
        FragmentMainDialogBinding dialogBinding = FragmentMainDialogBinding.inflate(LayoutInflater.from(requireContext()));
        final TextInputLayout textInputLayout = dialogBinding.dataTil;
        String hint;
        int maxKeyLength;
        int minKeyLength;
        boolean isSymmetric;

        switch (mFileViewModel.getAlgorithm()) {
            case AES:
                Objects.requireNonNull(textInputLayout.getEditText()).setInputType(EditorInfo.TYPE_CLASS_TEXT);
                minKeyLength = Constants.AES_MAX_KEY_SIZE;
                maxKeyLength = Constants.AES_MAX_KEY_SIZE;
                hint = getString(R.string.key_hint_aes, maxKeyLength);
                isSymmetric = true;
                break;
            case TRIPLE_DES:
                Objects.requireNonNull(textInputLayout.getEditText()).setInputType(EditorInfo.TYPE_CLASS_TEXT);
                minKeyLength = Constants.TRIPLE_DES_MIN_KEY_SIZE;
                maxKeyLength = Constants.TRIPLE_DES_MAX_KEY_SIZE;
                hint = String.format(Locale.getDefault(), getString(R.string.key_hint_3des), minKeyLength, maxKeyLength);
                isSymmetric = true;
                break;
            case RSA:
                Objects.requireNonNull(textInputLayout.getEditText()).setInputType(EditorInfo.TYPE_CLASS_NUMBER);
                minKeyLength = Constants.RSA_MIN_KEY_SIZE;
                maxKeyLength = Constants.RSA_MAX_KEY_SIZE;
                hint = String.format(Locale.getDefault(), getString(R.string.key_hint_rsa), minKeyLength, maxKeyLength);
                isSymmetric = false;
                break;
            case DIFFIE_HELLMAN:
                Objects.requireNonNull(textInputLayout.getEditText()).setInputType(EditorInfo.TYPE_CLASS_NUMBER);
                minKeyLength = Constants.DH_MIN_KEY_SIZE;
                maxKeyLength = Constants.DH_MAX_KEY_SIZE;
                hint = String.format(Locale.getDefault(), getString(R.string.key_hint_dh), minKeyLength, maxKeyLength);
                isSymmetric = false;
                break;
            default:
                throw new IllegalArgumentException("Unknown Algorithm.");
        }
        if (isSymmetric) textInputLayout.setCounterMaxLength(maxKeyLength);
        else textInputLayout.setCounterMaxLength(5);
        textInputLayout.setHint(hint);
        dialogBinding.dataEt.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!s.toString().isEmpty()) {
                    int charCount = s.length();
                    if (isSymmetric)
                        dialogBinding.dialogButton.setEnabled(charCount == minKeyLength || charCount == maxKeyLength);
                    else {
                        int size = Integer.parseInt(s.toString());
                        dialogBinding.dialogButton.setEnabled(size == minKeyLength || size == maxKeyLength || size % minKeyLength == 0 || ((size >= minKeyLength && size <= maxKeyLength) && (size % 64 == 0)));
                    }
                }
            }
        });
        final AlertDialog alertDialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogBinding.getRoot())
                .setCancelable(true).create();
        alertDialog.show();

        dialogBinding.dialogButton.setOnClickListener(v -> {
            alertDialog.dismiss();
            final String key = Objects.requireNonNull(dialogBinding.dataEt.getText()).toString();
            final FileData fileData = mFileViewModel.getFileData().getValue();
            if (fileData != null) {
                fileData.setKey(key);
                final String formattedKey = String.format(Locale.getDefault(), "Key: %s", fileData.getKey());
                mBinding.keyTv.setText(formattedKey);
                startEncryption(key);
            } else displaySnackBar("Upload a file.");
        });
    }

    private void changeViewState() {
        final FileData fileData = mFileViewModel.getFileData().getValue();
        if (fileData == null) disableCryptographyButtons();
        else {
            mBinding.encryptButton.setEnabled(true);
            mBinding.decryptButton.setEnabled(fileData.getEncryptedFile() != null);
        }
    }
}