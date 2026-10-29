// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.hierynomus.smbj.share

import com.hierynomus.mssmb2.SMB2FileId
import com.hierynomus.smbj.common.SmbPath

/** Exposes package-private [File] constructor to unit tests outside `com.hierynomus.smbj.share`. */
open class TestSmbFile(
    fileId: SMB2FileId,
    diskShare: DiskShare,
    smbPath: SmbPath,
) : File(fileId, diskShare, smbPath)
