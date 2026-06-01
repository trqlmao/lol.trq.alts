package lol.trq.alts.spi;

import java.nio.file.Path;

/**
 * Supplies the directory the encrypted alt store is read from and written to. The host owns the path
 * (for example a per-mod data directory under the game folder); the library never assumes a layout and
 * creates the directory if it is absent.
 *
 * @author trq
 * @since 0.1.0
 */
@FunctionalInterface
public interface VaultDirectoryProvider {

    /**
     * Returns the directory the account file lives in.
     *
     * @return the store base directory
     */
    Path vaultDirectory();
}
