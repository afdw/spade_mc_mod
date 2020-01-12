package spade_mc_mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.world.WorldProvider;
import net.minecraftforge.client.IRenderHandler;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.commons.io.IOUtils;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;

@Mod(modid = "spade_mc_mod", name = "spade_mc_mod", version = "1.0")
public class SpadeMcMod {
    private IntBuffer pixelBuffer;
    private int[] pixelValues;
    private boolean enabled = false;

    public void copyFromJar(String source, Path target) throws IOException, URISyntaxException {
        URI resource = getClass().getResource("").toURI();
        FileSystem fileSystem = FileSystems.newFileSystem(
                resource,
                Collections.<String, String>emptyMap()
        );
        Path jarPath = fileSystem.getPath(source);
        Files.walkFileTree(jarPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path currentTarget = target.resolve(jarPath.relativize(dir).toString());
                Files.createDirectories(currentTarget);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(jarPath.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) throws IOException, URISyntaxException {
        MinecraftForge.EVENT_BUS.register(this);
        copyFromJar("/spade_mc", Minecraft.getMinecraft().getResourcePackRepository().getDirResourcepacks().toPath().resolve("spade_mc"));
        Path optifineOptions = Minecraft.getMinecraft().mcDataDir.toPath().resolve("optionsof.txt");
        if (!Files.exists(optifineOptions)) {
            Files.copy(getClass().getResource("/optionsof.txt").openStream(), optifineOptions, StandardCopyOption.REPLACE_EXISTING);
        }
        GameSettings gameSettings = Minecraft.getMinecraft().gameSettings;
        boolean changed = false;
        if (gameSettings.ambientOcclusion != 0) {
            gameSettings.ambientOcclusion = 0;
            changed = true;
        }
        if (gameSettings.fancyGraphics) {
            gameSettings.fancyGraphics = false;
            changed = true;
        }
        if (Minecraft.getMinecraft().gameSettings.resourcePacks.isEmpty()) {
            Minecraft.getMinecraft().gameSettings.resourcePacks.add("spade_mc");
        }
        if (changed) {
            gameSettings.saveOptions();
        }
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (Keyboard.isKeyDown(Keyboard.KEY_GRAVE)) {
            enabled = !enabled;
        }
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (Minecraft.getMinecraft().player != null && Minecraft.getMinecraft().player.world != null) {
            WorldProvider worldProvider = Minecraft.getMinecraft().player.world.provider;
            worldProvider.setSkyRenderer(new IRenderHandler() {
                @Override
                public void render(float partialTicks, WorldClient world, Minecraft mc) {
                    float skyColor = 156F / 255F;
                    GL11.glClearColor(skyColor, skyColor, skyColor, 1.0F);
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                }
            });
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!enabled) {
            return;
        }

        Framebuffer framebuffer = Minecraft.getMinecraft().getFramebuffer();
        int width = framebuffer.framebufferTextureWidth;
        int height = framebuffer.framebufferTextureHeight;

        if (pixelBuffer == null || pixelBuffer.capacity() < width * height) {
            pixelBuffer = BufferUtils.createIntBuffer(width * height);
            pixelValues = new int[width * height];
        }

        GlStateManager.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GlStateManager.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GlStateManager.bindTexture(framebuffer.framebufferTexture);

        pixelBuffer.clear();
        GlStateManager.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer);
        pixelBuffer.get(pixelValues);
        TextureUtil.processPixelValues(pixelValues, width, height);
        BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        bufferedImage.setRGB(0, 0, width, height, pixelValues, 0, width);

        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ImageIO.write(bufferedImage, "png", byteArrayOutputStream);
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(byteArrayOutputStream.toByteArray());
            String boundary = "---" + System.currentTimeMillis() + "---";
            URL url = new URL("http://localhost:8000/");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            byteArrayOutputStream = new ByteArrayOutputStream();
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(byteArrayOutputStream, StandardCharsets.UTF_8), true);
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"in.png\"\r\n");
            writer.append("Content-Type: image/png\r\n");
            writer.append("\r\n");
            writer.flush();
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = byteArrayInputStream.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, bytesRead);
            }
            byteArrayOutputStream.flush();
            byteArrayInputStream.close();
            writer.append("\r\n");
            writer.append("\r\n");
            writer.append("--").append(boundary).append("--").append("\r\n");
            writer.close();
            byteArrayOutputStream.close();
            byteArrayInputStream = new ByteArrayInputStream(byteArrayOutputStream.toByteArray());
            OutputStream connectionOutputStream = connection.getOutputStream();
            IOUtils.copy(byteArrayInputStream, connectionOutputStream);
            connectionOutputStream.close();
            bufferedImage = ImageIO.read(connection.getInputStream());
        } catch (IOException e) {
            e.printStackTrace();
        }

        bufferedImage.getRGB(0, 0, width, height, pixelValues, 0, width);
        TextureUtil.processPixelValues(pixelValues, width, height);
        pixelBuffer.clear();
        pixelBuffer.put(pixelValues);
        pixelBuffer.clear();
        GL14.glWindowPos2i(0, 0);
        GL11.glDrawPixels(width, height, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer);
    }
}
