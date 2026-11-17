param(
    [string]$SkillRoot = (Join-Path $PSScriptRoot '..')
)

$ErrorActionPreference = 'Stop'
$resolvedRoot = (Resolve-Path -LiteralPath $SkillRoot).Path
$skillPath = Join-Path $resolvedRoot 'SKILL.md'
$skillText = [IO.File]::ReadAllText($skillPath).Replace("`r`n", "`n")
$frontmatter = [regex]::Match($skillText, '\A---\nname: (?<name>[a-z0-9]+(?:-[a-z0-9]+)*)\ndescription: (?<description>"[^\n]+")\n---\n')
if (-not $frontmatter.Success) {
    throw '이 스킬은 name과 JSON 따옴표 문자열 description을 가진 두 줄 frontmatter를 사용해야 합니다.'
}
$skillName = $frontmatter.Groups['name'].Value
$description = ConvertFrom-Json -InputObject $frontmatter.Groups['description'].Value
if ($skillName.Length -gt 64 -or $description -isnot [string] -or [string]::IsNullOrWhiteSpace($description) -or
        $description.Length -gt 1024 -or $description.Contains('<') -or $description.Contains('>')) {
    throw '스킬 이름 또는 description 형식/길이가 올바르지 않습니다.'
}
if ((Split-Path $resolvedRoot -Leaf) -ne $skillName) {
    throw '스킬 폴더 이름과 frontmatter name이 다릅니다.'
}
$linkCount = 0
foreach ($match in [regex]::Matches($skillText, '\[[^\]]*\]\(([^)]+)\)')) {
    $target = $match.Groups[1].Value
    if ($target -match '^(https?:|#)') { continue }
    $relativePath = ($target -split '#')[0]
    $resolvedTarget = [IO.Path]::GetFullPath((Join-Path $resolvedRoot $relativePath))
    if (-not $resolvedTarget.StartsWith($resolvedRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
            -not (Test-Path -LiteralPath $resolvedTarget)) {
        throw ('존재하지 않거나 스킬 폴더 밖의 참조입니다: ' + $target)
    }
    $linkCount++
}
if ($skillText -match '\[TODO|TODO:') {
    throw '스킬에 미완성 placeholder가 남아 있습니다.'
}
Write-Output ('Skill valid: ' + $skillName + '; references=' + $linkCount)
