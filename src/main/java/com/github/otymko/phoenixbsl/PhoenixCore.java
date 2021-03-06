package com.github.otymko.phoenixbsl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.otymko.phoenixbsl.logic.GlobalKeyListenerThread;
import com.github.otymko.phoenixbsl.logic.PhoenixAPI;
import com.github.otymko.phoenixbsl.logic.PhoenixUser32;
import com.github.otymko.phoenixbsl.logic.event.EventListener;
import com.github.otymko.phoenixbsl.logic.event.EventManager;
import com.github.otymko.phoenixbsl.logic.lsp.BSLConfiguration;
import com.github.otymko.phoenixbsl.logic.lsp.BSLLanguageClient;
import com.github.otymko.phoenixbsl.logic.utils.ProcessHelper;
import com.github.otymko.phoenixbsl.model.Configuration;
import com.sun.jna.platform.win32.WinDef;
import lombok.Data;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import com.github.otymko.phoenixbsl.logic.lsp.BSLBinding;
import com.github.otymko.phoenixbsl.gui.Toolbar;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

@Slf4j
@Data
public class PhoenixCore implements EventListener {

  private static final PhoenixCore INSTANCE = new PhoenixCore();

  private static final Path basePathApp = Path.of(System.getProperty("user.home"), "phoenixbsl");

  private static final Path pathToFolderLog = createPathToLog();
  private static final Path pathToConfiguration = createPathToConfiguration();
  private static final Path pathToBSLConfigurationDefault =
    Path.of(basePathApp.toString(), ".bsl-language-server.json");
  public static final URI fakeUri = Path.of("fake.bsl").toUri();
  public static final List<String> diagnosticListForQuickFix = createDiagnosticListForQuickFix();

  private EventManager events;
  private WinDef.HWND focusForm;
  private Process processBSL;
  private BSLBinding bslBinding = null;

  private Configuration configuration;

  private List<Diagnostic> diagnosticList = new ArrayList<>();

  public int currentOffset = 0;

  private PhoenixCore() {

    events = new EventManager(
      EventManager.EVENT_INSPECTION,
      EventManager.EVENT_FORMATTING,
      EventManager.EVENT_FIX_ALL,
      EventManager.EVENT_UPDATE_ISSUES,
      EventManager.SHOW_ISSUE_STAGE,
      EventManager.SHOW_SETTING_STAGE);
    events.subscribe(EventManager.EVENT_INSPECTION, this);
    events.subscribe(EventManager.EVENT_FORMATTING, this);
    events.subscribe(EventManager.EVENT_FIX_ALL, this);

    configuration = Configuration.create();


  }

  public static PhoenixCore getInstance() {
    return INSTANCE;
  }

  public void initProcessBSL() {
    createProcessBSLLS();
    if (processBSL != null) {
      connectToBSLLSProcess();
    }
  }


  // EventListener
  //

  @Override
  public void inspection() {

    LOGGER.debug("Событие: анализ кода");

    if (processBSLIsRunning() && PhoenixAPI.isWindowsForm1S()) {
      updateFocusForm();
    } else {
      return;
    }

    if (bslBinding == null) {
      return;
    }

    currentOffset = 0;
    var textForCheck = "";
    var textModuleSelected = PhoenixAPI.getTextSelected();
    if (textModuleSelected.length() > 0) {
      // получем номер строки
      textForCheck = textModuleSelected;
      currentOffset = PhoenixAPI.getCurrentLineNumber();
    } else {
      textForCheck = PhoenixAPI.getTextAll();
    }

    bslBinding.textDocumentDidChange(fakeUri, textForCheck);
    bslBinding.textDocumentDidSave(fakeUri);

  }

  @Override
  public void formatting() {

    LOGGER.debug("Событие: форматирование");

    if (!(processBSLIsRunning() && PhoenixAPI.isWindowsForm1S())) {
      return;
    }

    var textForFormatting = "";
    var isSelected = false;
    var textModuleSelected = PhoenixAPI.getTextSelected();
    if (textModuleSelected.length() > 0) {
      textForFormatting = textModuleSelected;
      isSelected = true;
    } else {
      textForFormatting = PhoenixAPI.getTextAll();
    }

    // DidChange
    bslBinding.textDocumentDidChange(fakeUri, textForFormatting);

    // Formatting
    var result = bslBinding.textDocumentFormatting(fakeUri);

    String newText = null;
    try {
      newText = result.get().get(0).getNewText();
    } catch (InterruptedException | ExecutionException e) {
      LOGGER.error("Ошибка получения форматированного текста", e);
    }

    if (newText != null) {
      PhoenixAPI.insetTextOnForm(newText, isSelected);
    }

  }

  @Override
  public void fixAll() {

    LOGGER.debug("Событие: обработка квикфиксов");

    if (!(processBSLIsRunning() && PhoenixAPI.isWindowsForm1S())) {
      return;
    }

    var separator = "\n";
    var textForQF = PhoenixAPI.getTextAll();

    // найдем все диагностики подсказки
    var listQF = diagnosticList.stream()
      .filter(this::isAcceptDiagnosticForQuickFix)
      //.filter(diagnostic -> diagnostic.getCode().equalsIgnoreCase("CanonicalSpellingKeywords"))
      .collect(Collectors.toList());

    List<Either<Command, CodeAction>> codeActions = new ArrayList<>();
    try {
      codeActions = bslBinding.textDocumentCodeAction(fakeUri, listQF);
    } catch (ExecutionException | InterruptedException e) {
      LOGGER.error(e.getMessage());

    }
    LOGGER.debug("Квикфиксов найдено: " + codeActions);
    String[] strings = textForQF.split(separator);

    try {
      applyAllQuickFixes(codeActions, strings);
    } catch (ArrayIndexOutOfBoundsException e) {
      LOGGER.error("При применении fix all к тексту модуля возникли ошибки", e);
      return;
    }

    if (!codeActions.isEmpty()) {
      var text = String.join(separator, strings);
      PhoenixAPI.insetTextOnForm(text, false);
    }

  }

  private void applyAllQuickFixes(List<Either<Command, CodeAction>> codeActions, String[] strings)
    throws ArrayIndexOutOfBoundsException {

    codeActions.forEach(diagnostic -> {
      CodeAction codeAction = diagnostic.getRight();
      if (codeAction.getTitle().startsWith("Fix all:")) {
        return;
      }
      codeAction.getEdit().getChanges().forEach((s, textEdits) -> {
        textEdits.forEach(textEdit -> {
          var range = textEdit.getRange();
          var currentLine = range.getStart().getLine();
          var newText = textEdit.getNewText();
          var currentString = strings[currentLine];
          var newString =
            currentString.substring(0, range.getStart().getCharacter())
              + newText
              + currentString.substring(range.getEnd().getCharacter());
          strings[currentLine] = newString;
        });
      });
    });

  }

  public void restartProcessBSLLS() {
    stopBSL();
    initProcessBSL();
  }


  public void createProcessBSLLS() {

    processBSL = null;

    var pathToBSLLS = Path.of(configuration.getPathToBSLLS()).toAbsolutePath();
    if (!pathToBSLLS.toFile().exists()) {
      LOGGER.error("Не найден BSL LS");
      return;
    }

    var arguments = ProcessHelper.getArgumentsRunProcessBSLLS(configuration);

    // TODO: вынести в отдельное место
    Path pathToBSLConfiguration = null;
    if (configuration.isUseCustomBSLLSConfiguration()) {
      Path path;
      try {
        path = Path.of(basePathApp.toString(), configuration.getPathToBSLLSConfiguration());
      } catch (InvalidPathException exp) {
        path = null;
      }
      if (path != null && path.toFile().exists()) {
        pathToBSLConfiguration = path;
      } else {
        pathToBSLConfiguration = Path.of(configuration.getPathToBSLLSConfiguration()).toAbsolutePath();
      }

    } else {
      initBSLConfiguration();
      pathToBSLConfiguration = pathToBSLConfigurationDefault;
    }

    if (pathToBSLConfiguration.toFile().exists()) {
      arguments.add("--configuration");
      arguments.add(pathToBSLConfiguration.toString());
    }

    LOGGER.debug("Строка запуска BSL LS {}", String.join(" ", arguments));

    try {
      processBSL = new ProcessBuilder()
        .command(arguments.toArray(new String[0]))
        .start();
      sleepCurrentThread(500);
      if (!processBSL.isAlive()) {
        processBSL = null;
        LOGGER.error("Не удалалось запустить процесс с BSL LS. Процесс был аварийно завершен.");
      }
    } catch (IOException e) {
      LOGGER.error("Не удалалось запустить процесс с BSL LS", e);
    }

  }

  public void connectToBSLLSProcess() {

    BSLLanguageClient bslClient = new BSLLanguageClient();
    BSLBinding bslBinding = new BSLBinding(
      bslClient,
      getProcessBSL().getInputStream(),
      getProcessBSL().getOutputStream());
    bslBinding.startInThread();

    sleepCurrentThread(2000);

    setBslBinding(bslBinding);

    // инициализация
    bslBinding.initialize();

    // откроем фейковый документ
    bslBinding.textDocumentDidOpen(getFakeUri(), "");

  }

  public void sleepCurrentThread(long value) {
    try {
      Thread.sleep(value);
    } catch (Exception e) {
      LOGGER.warn("Не удалось сделать паузу в текущем поток", e);
    }
  }

  public void startToolbar() {
    new Toolbar();
  }

  public boolean appIsRunning() {
    var thisPid = ProcessHandle.current().pid();
    var isRunning = new AtomicBoolean(false);
    ProcessHandle.allProcesses()
      .filter(
        ph -> ph.info().command().isPresent()
          && ph.info().command().get().contains("phoenixbsl")
          && ph.pid() != thisPid)
      .forEach((process) -> isRunning.set(true));
    return isRunning.get();
  }

  public void abort() {
    PhoenixAPI.showMessageDialog("Приложение уже запущено. Повторный запуск невозможен.");
    System.exit(0);
  }

  public boolean processBSLIsRunning() {
    return processBSL != null;
  }

  public Process getProcessBSL() {
    return processBSL;
  }

  public void setBslBinding(BSLBinding bslBinding) {
    this.bslBinding = bslBinding;
  }

  public EventManager getEventManager() {
    return events;
  }

  public void stopBSL() {
    if (bslBinding == null) {
      return;
    }
    bslBinding.shutdown();
    bslBinding.exit();
  }

  private void updateFocusForm() {
    focusForm = PhoenixUser32.getHWNDFocusForm();
  }

  public WinDef.HWND getFocusForm() {
    return focusForm;
  }

  public URI getFakeUri() {
    return fakeUri;
  }

  public void showIssuesStage() {
    events.notify(EventManager.SHOW_ISSUE_STAGE);
  }

  public String getVersionApp() {
    // взято из com/github/_1c_syntax/bsl/languageserver/cli/VersionCommand.java
    final var mfStream = Thread.currentThread()
      .getContextClassLoader()
      .getResourceAsStream("META-INF/MANIFEST.MF");

    var manifest = new Manifest();
    try {
      manifest.read(mfStream);
    } catch (IOException e) {
      LOGGER.error("Не удалось прочитать манифест проекта", e);
    }

    var version = "dev";
    if (manifest.getMainAttributes().get(Attributes.Name.MAIN_CLASS) == null) {
      return version;
    }
    version = manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
    return version;

  }

  public void showSettingStage() {

    events.notify(EventManager.SHOW_SETTING_STAGE);

  }

  public Path getPathToLogs() {
    return pathToFolderLog.toAbsolutePath();
  }

  public void initializeConfiguration() {
    // файл конфигурации должен лежать по пути: app/configuration.json
    var fileConfiguration = pathToConfiguration.toFile();
    if (!fileConfiguration.exists()) {
      // создать новый по умолчанию
      configuration = Configuration.create();
      writeConfiguration(configuration, fileConfiguration);
    } else {
      // прочитать в текущие настройки
      configuration = Configuration.create(fileConfiguration);
    }

  }

  public void writeConfiguration(Configuration configuration, File fileConfiguration) {
    // запишем ее в файл
    ObjectMapper mapper = new ObjectMapper();
    try {
      mapper.writeValue(fileConfiguration, configuration);
    } catch (IOException e) {
      LOGGER.error("Не удалось записать конфигурацию в файл.", e);
    }
  }

  public void writeConfiguration(Configuration configuration) {
    writeConfiguration(configuration, pathToConfiguration.toFile());
  }

  @SneakyThrows
  public String getVersionBSLLS() {
    var result = "<Неопределено>";
    var arguments = ProcessHelper.getArgumentsRunProcessBSLLS(configuration);
    arguments.add("--version");
    Process processBSL = null;
    try {
      processBSL = new ProcessBuilder().command(arguments.toArray(new String[0])).start();
    } catch (IOException e) {
      LOGGER.error(e.getMessage());
      return result;
    }

    if (processBSL == null) {
      return result;
    }

    var out = ProcessHelper.getStdoutProcess(processBSL);
    if (out == null) {
      return result;
    }
    if (out.startsWith("version")) {
      result = out.replaceAll("version: ", "");
    }
    return result;
  }

  public void initBSLConfiguration() {
    createBSLConfigurationFile();
  }

  public void createBSLConfigurationFile() {
    var bslConfiguration = new BSLConfiguration();
    bslConfiguration.setLanguage("ru");
    var codeLens = new BSLConfiguration.CodeLensOptions();
    codeLens.setShowCognitiveComplexity(false);
    codeLens.setShowCyclomaticComplexity(false);
    bslConfiguration.setCodeLens(codeLens);
    var diagnosticsOptions = new BSLConfiguration.DiagnosticsOptions();
    diagnosticsOptions.setComputeTrigger("onSave");
    diagnosticsOptions.setSkipSupport("never");
    bslConfiguration.setDiagnostics(diagnosticsOptions);
    bslConfiguration.setConfigurationRoot("src");

    pathToBSLConfigurationDefault.getParent().toFile().mkdirs();

    ObjectMapper mapper = new ObjectMapper();
    try {
      mapper.writeValue(pathToBSLConfigurationDefault.toFile(), bslConfiguration);
    } catch (IOException e) {
      LOGGER.error("Не удалось записать файл конфигурации BSL LS", e);
    }

  }

  private static Path createPathToConfiguration() {
    var path = Path.of(System.getProperty("user.home"), "phoenixbsl", "Configuration.json").toAbsolutePath();
    path.getParent().toFile().mkdirs();
    return path;
  }

  private static Path createPathToLog() {
    var path = Path.of(basePathApp.toString(), "logs").toAbsolutePath();
    path.toFile().mkdirs();
    return path;
  }

  private static List<String> createDiagnosticListForQuickFix() {
    var list = new ArrayList<String>();
    list.add("CanonicalSpellingKeywords");
    list.add("SpaceAtStartComment");
    list.add("SemicolonPresence");
    return list;
  }

  private boolean isAcceptDiagnosticForQuickFix(Diagnostic diagnostic) {
    return diagnosticListForQuickFix.contains(diagnostic.getCode());
  }

  public void initializeGlobalKeyListener() {
    new GlobalKeyListenerThread().start();
  }
}
